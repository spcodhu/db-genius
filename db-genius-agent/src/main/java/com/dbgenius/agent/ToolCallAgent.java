package com.dbgenius.agent;

import com.dbgenius.agent.compress.StepHistoryCondenser;
import com.dbgenius.agent.model.AgentState;
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

    private static final int DEFAULT_TOOL_OUTPUT_MAX_CHARS = 4000;

    private ChatResponse toolCallChatResponse;
    private AgentMessageSink messageSink;
    /** 工具输出截断安全网阈值（字符数），默认与 db-genius.context.tool-output-max-chars 一致，可通过 setter 覆盖 */
    private int toolOutputMaxChars = DEFAULT_TOOL_OUTPUT_MAX_CHARS;
    /** 单轮内步骤历史压缩器，默认不装配（null），行为与现状一致 */
    private StepHistoryCondenser stepCondenser;
    /** 本轮 Agent 生成内容在 messageList 中的起始下标（历史消息 + 当前用户输入之后），由 onStepStart 记录 */
    private int turnStartIndex;

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

    public void setToolOutputMaxChars(int toolOutputMaxChars) {
        this.toolOutputMaxChars = toolOutputMaxChars;
    }

    public void setStepCondenser(StepHistoryCondenser stepCondenser) {
        this.stepCondenser = stepCondenser;
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
        if (stepCondenser != null) {
            Integer contextWindow = tokenUsageAccumulator != null ? tokenUsageAccumulator.getContextWindow() : null;
            stepCondenser.condenseIfNeeded(messageList, turnStartIndex, systemPrompt, reasoningChatModel, contextWindow);
        }

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
     * 对一步工具执行结果做截断安全网：逐条检查 {@link ToolResponseMessage.ToolResponse#responseData()}，
     * 超过 {@link #toolOutputMaxChars} 则替换为"头部预览 + 尾部预览 + 已省略字符数"的形式。
     */
    private ToolResponseMessage truncateToolResponses(ToolResponseMessage original) {
        List<ToolResponseMessage.ToolResponse> truncated = original.getResponses().stream()
                .map(this::truncateToolResponse)
                .toList();
        return ToolResponseMessage.builder().responses(truncated).build();
    }

    private ToolResponseMessage.ToolResponse truncateToolResponse(ToolResponseMessage.ToolResponse response) {
        String data = response.responseData();
        if (data == null || data.length() <= toolOutputMaxChars) {
            return response;
        }
        int headLen = toolOutputMaxChars * 2 / 3;
        int tailLen = Math.max(0, toolOutputMaxChars - headLen);
        String head = data.substring(0, headLen);
        String tail = tailLen > 0 ? data.substring(data.length() - tailLen) : "";
        int omitted = data.length() - head.length() - tail.length();
        String preview = head + "\n...(已省略 " + omitted + " 字符，工具 " + response.name()
                + " 原始结果共 " + data.length() + " 字符，已截断以避免 context 膨胀)...\n" + tail;
        log.warn("[{}] Truncated oversized tool response from '{}': {} -> {} chars",
                name, response.name(), data.length(), preview.length());
        return new ToolResponseMessage.ToolResponse(response.id(), response.name(), preview);
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

    private static final String SUMMARY_SYSTEM_PROMPT = """
            You are a concise Markdown summarizer. Your job is to produce the final summary of a database task.

            ## Output Rules
            1. Output ONLY a Markdown document. Do not wrap it in code fences unless you are showing code/SQL.
            2. If the task returned query data (rows/columns), present the results as a Markdown table with a clear header.
               - Use the real column names from the result.
               - Limit the table to at most 100 rows; if there are more, add a note like “（共 N 条，仅展示前 100 条）”.
            3. For non-query tasks (data import, schema changes, database comparison, etc.), use Markdown sections, bullet lists, and code blocks to summarize what was done and the final conclusion.
            4. Keep the summary concise but complete: state what action was taken and the outcome.
            5. Respond in the same language as the user's original request.
            6. You have NO tools available in this turn. NEVER output tool-call markup of any kind
               (no tool_calls/invoke blocks, no XML-like tags, no internal markers). Output Markdown text only.
            """;

    private static final String SUMMARY_USER_PROMPT_TEMPLATE = """
            Original user request:
            %s

            Based on the task execution history above, generate the final Markdown summary.
            If the history contains query results, output them as a Markdown table.
            """;

    @Override
    protected void onFinish(SseEmitter emitter, String userPrompt) throws Exception {
        // summary 是一次全量上下文的调用，耗时较长，先推状态避免前端误以为连接已断
        sendEvent(emitter, SseEvent.of(taskId, currentStep, "thinking", "正在生成总结..."));
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

    private String generateMarkdownSummary(String userPrompt, SseEmitter emitter) {
        try {
            List<Message> summaryMessages = new ArrayList<>(messageList.size() + 2);
            summaryMessages.add(new SystemMessage(SUMMARY_SYSTEM_PROMPT));
            summaryMessages.addAll(messageList);
            summaryMessages.add(new UserMessage(SUMMARY_USER_PROMPT_TEMPLATE.formatted(userPrompt)));

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
}
