package com.dbgenius.agent;

import com.dbgenius.agent.compress.ContextCompactListener;
import com.dbgenius.agent.compress.ObservationElider;
import com.dbgenius.agent.compress.StepHistoryCondenser;
import com.dbgenius.agent.guard.LoopBreaker;
import com.dbgenius.agent.model.AgentState;
import com.dbgenius.agent.prompt.PromptTemplateLoader;
import com.dbgenius.agent.tool.guard.ToolOutputGuard;
import com.dbgenius.common.i18n.MessageService;
import com.dbgenius.model.vo.ContextCompactVO;
import com.dbgenius.model.vo.SseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ToolCallAgent extends ReActAgent {

    /**
     * Agent 每步消息的落库回调，由 Handler 装配（类似 {@link #setSummaryCallback}）。
     * 默认空实现，不装配则行为与现状一致（仅内存流转、不落过程消息）。
     */
    public interface AgentMessageSink {

        /**
         * 一步思考完成的 assistant 消息。
         *
         * @param step             步骤号（1 起，与 SSE step 对齐）
         * @param content          模型正文（纯工具调用轮次可能为空串）
         * @param reasoningContent 归一化后的思考内容，无则为 null
         * @param toolCallsJson    [{id,type,name,arguments}] JSON 文本，无工具调用则为 null
         */
        default void onAssistant(int step, String content, String reasoningContent, String toolCallsJson) {
        }

        /**
         * 一步工具执行结果。
         *
         * @param step               步骤号
         * @param toolResponsesJson  [{id,name,result}] JSON 文本（一步多工具时为数组）
         */
        default void onToolResponses(int step, String toolResponsesJson) {
        }
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    protected final ToolCallback[] availableTools;
    protected final ReasoningChatModel reasoningChatModel;
    protected final ToolCallingManager toolCallingManager;
    protected final ChatOptions chatOptions;

    private ChatResponse toolCallChatResponse;
    private AgentMessageSink messageSink;
    /** 工具输出超长拦截器（始终生效）：默认用一份独立默认实例，生产由 Handler 注入 Spring 管理的单例 */
    private ToolOutputGuard toolOutputGuard = ToolOutputGuard.withDefaults();
    /** 单轮内步骤历史压缩器（Tier-3 LLM 摘要兜底），默认不装配（null），行为与现状一致 */
    private StepHistoryCondenser stepCondenser;
    /** 单轮内确定性观测遮蔽（Tier-1，零 LLM 成本），默认不装配（null） */
    private ObservationElider observationElider;
    /** 死循环护栏（单次运行内状态），默认不装配（null），行为与现状一致 */
    private LoopBreaker loopBreaker;
    /** 本步是否发生过工具输出截断，供 LoopBreaker 累计计数 */
    private boolean truncatedThisStep;
    /** 本轮 Agent 生成内容在 messageList 中的起始下标（历史消息 + 当前用户输入之后），由 onStepStart 记录 */
    private int turnStartIndex;
    /** 本轮请求的语言环境：驱动 prompt 模板选择与总结输出语言，由具体 Agent 构造时设置 */
    private Locale locale = Locale.ENGLISH;
    /** 消息文案服务（可选装配）：装配后 SSE 状态文案按 locale 本地化，未装配保持旧的硬编码兜底 */
    private MessageService messageService;

    public ToolCallAgent(String name, String systemPrompt, String nextStepPrompt,
                         int maxSteps, ReasoningChatModel reasoningChatModel,
                         Object... toolObjects) {
        this(name, systemPrompt, nextStepPrompt, maxSteps, reasoningChatModel, null, toolObjects);
    }

    public ToolCallAgent(String name, String systemPrompt, String nextStepPrompt,
                         int maxSteps, ReasoningChatModel reasoningChatModel,
                         Map<String, Object> toolContext, Object... toolObjects) {
        super(name, maxSteps);
        this.systemPrompt = systemPrompt;
        this.nextStepPrompt = nextStepPrompt;
        this.reasoningChatModel = reasoningChatModel;
        this.availableTools = ToolCallbacks.from(toolObjects);
        this.toolCallingManager = ToolCallingManager.builder().build();
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                .toolCallbacks(this.availableTools)
                .internalToolExecutionEnabled(false);
        if (toolContext != null) {
            // toolContext 随 chatOptions 进入 Prompt，executeToolCalls 时自动传给声明了
            // ToolContext 参数的 @Tool 方法（LLM 不可见）
            optionsBuilder.toolContext(toolContext);
        }
        this.chatOptions = optionsBuilder.build();
    }

    public void setMessageSink(AgentMessageSink messageSink) {
        this.messageSink = messageSink;
    }

    public void setToolOutputGuard(ToolOutputGuard toolOutputGuard) {
        if (toolOutputGuard != null) {
            this.toolOutputGuard = toolOutputGuard;
        }
    }

    public void setStepCondenser(StepHistoryCondenser stepCondenser) {
        this.stepCondenser = stepCondenser;
    }

    public void setObservationElider(ObservationElider observationElider) {
        this.observationElider = observationElider;
    }

    public void setLoopBreaker(LoopBreaker loopBreaker) {
        this.loopBreaker = loopBreaker;
    }

    /**
     * 单轮内上下文瘦身的阶梯调度：先做零成本的 Tier-1 确定性观测遮蔽，遮蔽之后仍逼近窗口
     * 才升级到 Tier-3 的 LLM 摘要。两档都通过 {@code context_compact} SSE 事件对前端可见。
     *
     * <p>任何异常都不允许打断 ReAct 循环——瘦身失败只是上下文更大，模型仍可继续工作。</p>
     */
    private void compactContextIfNeeded() {
        Integer contextWindow = tokenUsageAccumulator != null ? tokenUsageAccumulator.getContextWindow() : null;
        if (contextWindow == null || contextWindow <= 0) {
            return;
        }
        try {
            if (observationElider != null) {
                ObservationElider.Result result = observationElider.elideIfNeeded(
                        messageList, turnStartIndex, systemPrompt, contextWindow, taskId);
                if (result.elided()) {
                    // 毫秒级操作，只推 end，避免前端卡片闪烁
                    sendCompactEvent(ContextCompactVO.builder()
                            .phase("end")
                            .tier(ContextCompactListener.TIER_ELIDE)
                            .message(compactedText(ContextCompactListener.TIER_ELIDE,
                                    result.beforeTokens() - result.afterTokens()))
                            .beforeTokens(result.beforeTokens())
                            .afterTokens(result.afterTokens())
                            .affectedUnits(result.elidedCount())
                            .build());
                }
            }
            if (stepCondenser != null) {
                stepCondenser.condenseIfNeeded(messageList, turnStartIndex, systemPrompt, reasoningChatModel,
                        contextWindow, locale, summarizeCompactListener());
            }
        } catch (Exception e) {
            log.warn("[{}] in-run context compaction skipped: {}", name, e.getMessage());
        }
    }

    /** Tier-3 是秒级 LLM 调用，start/end 都推，消灭「莫名等待」。 */
    private ContextCompactListener summarizeCompactListener() {
        return new ContextCompactListener() {
            @Override
            public void onCompactStart(String tier) {
                sendCompactEvent(ContextCompactVO.builder()
                        .phase("start")
                        .tier(tier)
                        .message(compactingText())
                        .build());
            }

            @Override
            public void onCompactEnd(String tier, int beforeTokens, int afterTokens, int affectedUnits) {
                sendCompactEvent(ContextCompactVO.builder()
                        .phase("end")
                        .tier(tier)
                        .message(compactedText(tier, beforeTokens - afterTokens))
                        .beforeTokens(beforeTokens)
                        .afterTokens(afterTokens)
                        .affectedUnits(affectedUnits)
                        .build());
            }
        };
    }

    private void sendCompactEvent(ContextCompactVO payload) {
        if (emitter != null) {
            sendEvent(emitter, SseEvent.of(taskId, currentStep, "context_compact", payload));
        }
    }

    private String compactingText() {
        return messageService != null ? messageService.get("chat.compacting", locale) : "正在压缩上下文...";
    }

    private String compactedText(String tier, int savedTokens) {
        int saved = Math.max(0, savedTokens);
        if (messageService != null) {
            return messageService.get("chat.compacted." + tier, locale, saved);
        }
        return "上下文已压缩，约节省 " + saved + " tokens";
    }

    public void setLocale(Locale locale) {
        this.locale = locale != null ? locale : Locale.ENGLISH;
    }

    public void setMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
        if (!historyMessages.isEmpty()) {
            messageList.addAll(historyMessages);
        }
        messageList.add(new UserMessage(userPrompt));
        // 记录本轮 Agent 自己生成内容的起始下标：单轮内压缩（StepHistoryCondenser）只作用于此下标之后的
        // 步骤消息，历史消息与当前用户输入永远不动，与跨轮压缩职责边界清晰分离
        turnStartIndex = messageList.size();
        sendEvent(emitter, SseEvent.of(taskId, 0, "thinking", "Analyzing your request..."));
    }

    /**
     * 执行模型思考
     * @return 返回值表示是否需要执行 tool
     */
    @Override
    protected boolean think(){
        compactContextIfNeeded();

        if (nextStepPrompt != null && !nextStepPrompt.isBlank() && currentStep > 1) {
            messageList.add(new UserMessage(nextStepPrompt));
        }

        // 原先经 ChatClient 的 .system(systemPrompt) 注入，等价于在消息列表头部加 SystemMessage
        List<Message> requestMessages = new ArrayList<>(messageList.size() + 1);
        requestMessages.add(new SystemMessage(systemPrompt));
        requestMessages.addAll(messageList);
        Prompt prompt = new Prompt(requestMessages, chatOptions);

        // 流式聚合调用：reasoning 增量实时推给前端（阻塞 call() 在 thinking 模式下会静默
        // 数十秒才一次性出结果，SSE 表现为步骤成批到达）；返回的完整响应与 call() 同构，
        // metadata 带完整 reasoningContent，后续轮次由 ReasoningChatModel 回传给 DeepSeek
        ChatResponse response = reasoningChatModel.streamAggregated(prompt,
                reasoningDelta -> sendEvent(emitter, SseEvent.of(taskId, currentStep, "reasoning", reasoningDelta)));

        if (response == null || response.getResult() == null) {
            log.warn("[{}] Empty response from LLM", name);
            return false;
        }

        // DSML 降级恢复：模型偶发把真实工具参数写进 content 的 DSML 文本、
        // 而结构化 tool_calls 的 arguments 为空。此处反解析恢复真实参数，
        // 保证 act() 执行的是真参数调用、消息历史保持结构化协议（治本）
        response = recoverDsmlToolCalls(response);

        this.toolCallChatResponse = response;

        AssistantMessage assistantMessage = response.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

        String result = assistantMessage.getText();
        log.info("[{}] thinking: {}", name, result);

        // 落库回调：本步思考内容 + 模型发出的工具调用记录（无论是否调用工具都记录）
        if (messageSink != null) {
            messageSink.onAssistant(currentStep, result != null ? result : "",
                    ReasoningChatModel.normalizeReasoningContent(assistantMessage.getMetadata()),
                    toToolCallsJson(toolCalls));
        }

        if (toolCalls == null || toolCalls.isEmpty()) {
            messageList.add(assistantMessage);
            return false;
        }

        log.info("[{}] selected {} tools: {}", name, toolCalls.size(),
                toolCalls.stream().map(AssistantMessage.ToolCall::name)
                        .collect(Collectors.joining(", ")));
        return true;
    }

    @Override
    protected String act() throws Exception {
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "No tools to call.";
        }

        // 空参/非法参数护栏：DSML 恢复后仍有 arguments 空白的调用时，
        // 绝不执行空参调用（莫名错误会把模型推向 DSML 文本螺旋），
        // 改为合成可行动的重试反馈
        List<AssistantMessage.ToolCall> calls = toolCallChatResponse.getResult().getOutput().getToolCalls();
        if (calls.stream().anyMatch(tc -> !isValidArguments(tc.arguments()))) {
            return rejectInvalidArgumentCalls(calls);
        }

        // 死循环护栏：同一 (工具名 + 参数) 反复调用时不再真正执行，改为可行动的改变策略指引。
        // 常见触发场景是工具输出被截断或语句超时后模型原样重试，只靠 maxSteps 兜底
        // 意味着用户要为整整一轮无效模型调用付费并等待。
        if (loopBreaker != null) {
            List<String> blocked = loopBreaker.inspect(calls);
            if (!blocked.isEmpty()) {
                return rejectBlockedCalls(calls, blocked);
            }
        }

        Prompt prompt = new Prompt(messageList, chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        List<Message> history = toolExecutionResult.conversationHistory();
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) history.get(history.size() - 1);

        // 工具输出截断安全网（始终生效）：单条工具结果超过阈值时，只截断写入 messageList
        // （下一次发给模型的 prompt）的版本，避免超大结果集/文档内容把单轮 context 撑爆；
        // messageSink 落库、SSE 展示仍使用未截断的原始结果，审计轨迹与前端展示不受影响。
        List<Message> boundedHistory = new ArrayList<>(history);
        boundedHistory.set(boundedHistory.size() - 1, truncateToolResponses(toolResponseMessage));
        messageList.clear();
        messageList.addAll(boundedHistory);

        // todo 结束标识符在 systemPrompt 里面输入给模型了，这里直接硬编码，后面可以考虑更优雅的方式。
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));

        if (terminateToolCalled) {
            state = AgentState.FINISHED;
            log.info("[{}] Terminate tool called, finishing", name);
        }

        // 多次截断后注入一次「改用聚合/分页」的系统指引：必须在 tool_response 之后追加，
        // 保证 assistant → tool → user 的消息顺序合法
        if (loopBreaker != null && truncatedThisStep && loopBreaker.recordTruncationAndNeedsHint()) {
            messageList.add(new UserMessage(loopBreaker.narrowingHint()));
        }

        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "Tool " + response.name() + " result: " + response.responseData())
                .collect(Collectors.joining("\n"));

        // 落库回调：本步工具执行结果
        if (messageSink != null) {
            messageSink.onToolResponses(currentStep, toToolResponsesJson(toolResponseMessage.getResponses()));
        }

        log.info("[{}] act results: {}", name,
                results.length() > 300 ? results.substring(0, 300) + "..." : results);
        return results;
    }

    /**
     * 对一步工具执行结果做超长拦截：逐条交给 {@link ToolOutputGuard} 做结构感知截断
     * （行集按行裁、其余封装为合法 JSON 信封），并把完整原文登记进制品仓换取 artifactId，
     * 模型可用 {@code readToolOutput} 分页取回，避免「看不到数据 → 重试同一条语句」的死循环。
     */
    private ToolResponseMessage truncateToolResponses(ToolResponseMessage original) {
        truncatedThisStep = false;
        List<ToolResponseMessage.ToolResponse> guarded = original.getResponses().stream()
                .map(response -> {
                    String bounded = toolOutputGuard.guard(taskId, response.name(), response.responseData());
                    if (bounded == null || bounded.equals(response.responseData())) {
                        return response;
                    }
                    truncatedThisStep = true;
                    return new ToolResponseMessage.ToolResponse(response.id(), response.name(), bounded);
                })
                .toList();
        return ToolResponseMessage.builder().responses(guarded).build();
    }

    /**
     * 重复调用护栏的执行体：不真实执行，手工把 assistant 输出与合成的 ToolResponseMessage
     * （id 与 tool call 一致）写入 messageList。触发硬熔断时追加收尾指引并把 maxSteps 收敛到
     * 当前步 + 1——<b>仍走正常的 onFinish/summary 流程优雅收尾</b>，而不是抛错中断。
     */
    private String rejectBlockedCalls(List<AssistantMessage.ToolCall> calls, List<String> feedback) {
        messageList.add(toolCallChatResponse.getResult().getOutput());
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), feedback.get(i)));
        }
        messageList.add(ToolResponseMessage.builder().responses(responses).build());

        if (loopBreaker.isHardStopTriggered()) {
            messageList.add(new UserMessage(loopBreaker.hardStopHint()));
            int convergedMaxSteps = Math.min(maxSteps, currentStep + 1);
            if (convergedMaxSteps != maxSteps) {
                log.warn("[{}] Hard stop triggered, converging maxSteps {} -> {}", name, maxSteps, convergedMaxSteps);
                maxSteps = convergedMaxSteps;
            }
        }

        if (messageSink != null) {
            messageSink.onToolResponses(currentStep, toToolResponsesJson(responses));
        }
        return responses.stream()
                .map(response -> "Tool " + response.name() + " result: " + response.responseData())
                .collect(Collectors.joining("\n"));
    }

    /**
     * DSML 降级恢复（治本）。DeepSeek thinking+流式偶发把真实工具参数写进 content 的
     * DSML 标记、而结构化 tool_calls 的 arguments 为空（甚至整体缺失）。此处：
     * ① arguments 空白的结构化调用按顺序用 DSML 反解析结果填补参数；
     * ② 无结构化调用时合成新调用；
     * ③ content 剥离 DSML 文本后重建 AssistantMessage（保留 metadata，
     *    reasoning_content 回传不受影响），使消息历史保持结构化协议干净，
     *    不再向后续轮次暴露 DSML 文本。
     */
    private ChatResponse recoverDsmlToolCalls(ChatResponse response) {
        AssistantMessage output = response.getResult().getOutput();
        String content = output.getText();
        if (content == null || !DsmlToolCallParser.containsDsml(content)) {
            return response;
        }
        List<DsmlToolCallParser.RecoveredToolCall> recovered = DsmlToolCallParser.parse(content);
        if (recovered.isEmpty()) {
            return response;
        }

        List<AssistantMessage.ToolCall> structured = output.getToolCalls();
        List<AssistantMessage.ToolCall> rebuiltCalls = new ArrayList<>();
        if (structured != null && !structured.isEmpty()) {
            for (int i = 0; i < structured.size(); i++) {
                AssistantMessage.ToolCall tc = structured.get(i);
                if (isValidArguments(tc.arguments())) {
                    rebuiltCalls.add(tc);
                } else if (i < recovered.size()) {
                    DsmlToolCallParser.RecoveredToolCall rc = recovered.get(i);
                    String toolName = tc.name() != null && !tc.name().isBlank() ? tc.name() : rc.name();
                    rebuiltCalls.add(new AssistantMessage.ToolCall(tc.id(), tc.type(), toolName, rc.argumentsJson()));
                } else {
                    rebuiltCalls.add(tc);
                }
            }
            for (int i = structured.size(); i < recovered.size(); i++) {
                DsmlToolCallParser.RecoveredToolCall rc = recovered.get(i);
                rebuiltCalls.add(new AssistantMessage.ToolCall("dsml-" + i, "function", rc.name(), rc.argumentsJson()));
            }
        } else {
            for (int i = 0; i < recovered.size(); i++) {
                DsmlToolCallParser.RecoveredToolCall rc = recovered.get(i);
                rebuiltCalls.add(new AssistantMessage.ToolCall("dsml-" + i, "function", rc.name(), rc.argumentsJson()));
            }
        }

        AssistantMessage rebuilt = AssistantMessage.builder()
                .content(DsmlToolCallParser.strip(content))
                .properties(output.getMetadata())
                .toolCalls(rebuiltCalls)
                .build();
        log.warn("[{}] Recovered {} DSML tool call(s) from content text", name, rebuiltCalls.size());
        return new ChatResponse(
                List.of(new Generation(rebuilt, response.getResult().getMetadata())), response.getMetadata());
    }

    /** arguments 必须是合法 JSON 对象才算可执行，空白/非对象一律拒绝 */
    private boolean isValidArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(arguments).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 空参/非法参数护栏的执行体：不真实执行，手工把 assistant 输出与合成的
     * ToolResponseMessage（id 与 tool call 一致）写入 messageList，工具结果为
     * 可行动的重试指引——模型能理解并纠正，而非收到 "config not found for ID null" 式噪音。
     */
    private String rejectInvalidArgumentCalls(List<AssistantMessage.ToolCall> calls) {
        String feedback = "{\"success\":false,\"error\":\"Tool arguments were empty or invalid. "
                + "Re-emit the tool call with all required parameters.\"}";
        messageList.add(toolCallChatResponse.getResult().getOutput());
        List<ToolResponseMessage.ToolResponse> responses = calls.stream()
                .map(tc -> new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), feedback))
                .toList();
        messageList.add(ToolResponseMessage.builder().responses(responses).build());
        if (messageSink != null) {
            messageSink.onToolResponses(currentStep, toToolResponsesJson(responses));
        }
        log.warn("[{}] Rejected {} tool call(s) with empty/invalid arguments", name, calls.size());
        return calls.stream()
                .map(tc -> "Tool " + tc.name() + " result: " + feedback)
                .collect(Collectors.joining("\n"));
    }

    /** 工具调用记录序列化为 [{id,type,name,arguments}] JSON；无调用返回 null。 */
    private String toToolCallsJson(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (Exception e) {
            log.warn("[{}] Failed to serialize tool calls", name, e);
            return null;
        }
    }

    /** 工具执行结果序列化为 [{id,name,result}] JSON（一步多工具时为数组）；无结果返回 null。 */
    private String toToolResponsesJson(List<ToolResponseMessage.ToolResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> records = new ArrayList<>(responses.size());
            for (ToolResponseMessage.ToolResponse response : responses) {
                Map<String, Object> record = new java.util.LinkedHashMap<>();
                record.put("id", response.id());
                record.put("name", response.name());
                record.put("result", response.responseData());
                records.add(record);
            }
            return objectMapper.writeValueAsString(records);
        } catch (Exception e) {
            log.warn("[{}] Failed to serialize tool responses", name, e);
            return null;
        }
    }

    private static final String SUMMARY_PROMPT_TEMPLATE = "tool-call-summary";

    @Override
    protected void onFinish(SseEmitter emitter, String userPrompt) throws Exception {
        // summary 是一次全量上下文的调用，耗时较长，先推状态避免前端误以为连接已断
        sendEvent(emitter, SseEvent.of(taskId, currentStep, "thinking", summaryStatusText()));
        String markdown = generateMarkdownSummary(userPrompt, emitter);
        sendEvent(emitter, SseEvent.of(taskId, currentStep, "summary", markdown));
        if (summaryCallback != null) {
            try {
                summaryCallback.accept(markdown);
            } catch (Exception e) {
                log.warn("[{}] summary callback failed: {}", name, e.getMessage());
            }
        }
    }

    /** "正在生成总结"状态文案：装配了 MessageService 时按本轮 locale 本地化，否则保持旧的硬编码兜底 */
    private String summaryStatusText() {
        if (messageService != null) {
            return messageService.get("chat.summarizing", locale);
        }
        return "正在生成总结...";
    }

    private String generateMarkdownSummary(String userPrompt, SseEmitter emitter) {
        try {
            String[] sections = PromptTemplateLoader.splitSections(
                    PromptTemplateLoader.load(SUMMARY_PROMPT_TEMPLATE, locale));
            String systemPrompt = PromptTemplateLoader.withOutputLanguage(sections[0], locale);
            String userSection = sections.length > 1
                    ? PromptTemplateLoader.render(sections[1], Map.of("message", userPrompt))
                    : userPrompt;

            List<Message> summaryMessages = new ArrayList<>(messageList.size() + 2);
            summaryMessages.add(new SystemMessage(systemPrompt));
            summaryMessages.addAll(messageList);
            summaryMessages.add(new UserMessage(userSection));

            // 流式聚合：reasoning/content 增量实时推前端（打字机效果），聚合全文作为
            // 终态 summary 事件与落库内容；终态事件同时是前端渲染的权威兜底
            ChatResponse response = reasoningChatModel.streamAggregated(new Prompt(summaryMessages),
                    reasoningDelta -> sendEvent(emitter, SseEvent.of(taskId, currentStep, "reasoning", reasoningDelta)),
                    contentDelta -> sendEvent(emitter, SseEvent.of(taskId, currentStep, "summary_delta", contentDelta)));

            String markdown = response != null && response.getResult() != null
                    ? response.getResult().getOutput().getText() : null;
            // 最后安全网（非主修复）：终态 summary 是权威落库内容，剥离任何 DSML 残留，
            // 空白则走 fallback；前端以终态事件覆盖打字机增量
            if (markdown != null) {
                markdown = DsmlToolCallParser.strip(markdown);
            }
            if (markdown == null || markdown.isBlank()) {
                return fallbackSummary(userPrompt);
            }
            return markdown;
        } catch (Exception e) {
            log.error("[{}] Failed to generate Markdown summary", name, e);
            return fallbackSummary(userPrompt);
        }
    }

    private String fallbackSummary(String userPrompt) {
        String lastResult = messageList.stream()
                .filter(m -> m instanceof AssistantMessage || m instanceof ToolResponseMessage)
                .reduce((first, second) -> second)
                .map(Message::getText)
                .orElse("Task completed.");
        return "## Summary\n\n" + lastResult;
    }

    /**
     * 运行结束（正常完成 / 异常 / SSE 超时或断开）时释放本任务登记的工具输出制品，
     * 避免长会话与异常中断堆积内存。制品仓另有 TTL 兜底，此处是主动路径。
     */
    @Override
    protected void cleanup() {
        try {
            toolOutputGuard.evictTask(taskId);
        } catch (Exception e) {
            log.warn("[{}] Failed to evict tool output artifacts: {}", name, e.getMessage());
        }
        super.cleanup();
    }
}
