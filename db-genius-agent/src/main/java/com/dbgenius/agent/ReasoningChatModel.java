package com.dbgenius.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.OpenAiApi.FunctionTool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 支持 DeepSeek thinking 模式的 ChatModel 装饰器。
 *
 * <p>背景：DeepSeek V4 默认开启 thinking 模式，发生 tool call 的轮次要求后续请求
 * 必须回传 {@code reasoning_content}，否则 API 返回 400。Spring AI 1.1.x 的
 * {@link OpenAiChatModel} 只在响应侧解析 {@code reasoningContent}（放入
 * {@link AssistantMessage} metadata），构造请求时并不回传。本类在 {@code call()}
 * 路径上补齐回传逻辑，其余行为与官方实现对齐；{@code stream()} 直接委托给官方模型
 * （流式 simple chat 不涉及 tool call，无需回传）。
 *
 * <p>{@link #streamAggregated} 供工具调用 Agent 使用：流式拉取（reasoning 增量实时
 * 回调，避免 think 阶段长时间无事件），内部聚合成与 {@code call()} 同构的响应，
 * 保留 tool call 轮次的 reasoning_content 回传能力。
 *
 * <p>仅支持文本消息（本项目无多模态场景）；不含重试与观测埋点。
 * 未来升级到 Spring AI 2.0（原生支持回传）后本类可整体删除。
 */
@Slf4j
public class ReasoningChatModel implements ChatModel {

    /** 与 {@link OpenAiChatModel} 一致的 metadata key，下游统一按此读取推理内容。 */
    public static final String REASONING_CONTENT_KEY = "reasoningContent";

    private final OpenAiApi openAiApi;
    private final OpenAiChatModel delegate;
    private final OpenAiChatOptions defaultOptions;
    private final ToolCallingManager toolCallingManager;

    public ReasoningChatModel(OpenAiApi openAiApi, OpenAiChatModel delegate,
                              OpenAiChatProperties chatProperties, ToolCallingManager toolCallingManager) {
        this.openAiApi = openAiApi;
        this.delegate = delegate;
        this.defaultOptions = chatProperties.getOptions();
        this.toolCallingManager = toolCallingManager;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        OpenAiChatOptions requestOptions = mergeOptions(prompt.getOptions());
        ChatCompletionRequest request = createRequest(prompt.getInstructions(), requestOptions, false);
        ChatCompletion chatCompletion = this.openAiApi.chatCompletionEntity(request).getBody();
        Assert.notNull(chatCompletion, "chat completion must not be null");
        return toChatResponse(chatCompletion);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return this.delegate.stream(prompt);
    }

    /**
     * 流式执行一次补全，reasoning 增量实时回调，流结束后聚合成与 {@link #call(Prompt)}
     * 完全同构的 {@link ChatResponse} 返回。
     *
     * <p>存在的意义：Agent 的 think() 若用阻塞 {@code call()}，DeepSeek thinking 模式下
     * 单步要静默数十秒才一次性拿到结果，SSE 前端表现为"步骤成批到达、不实时"。改为
     * 流式后推理内容随生成随推送；聚合结果（完整 reasoning_content + 合并分片后的
     * tool calls）继续驱动原有的 tool call 判定与 reasoning_content 回传流程，下游零改动。
     *
     * <p>必须走 {@link #createRequest} 自建请求（而非 {@code delegate.stream()}），
     * 否则历史消息中的 reasoning_content 回传会丢失，tool call 轮次 DeepSeek 返回 400。
     *
     * @param reasoningDeltaConsumer 每收到一段 reasoning 增量回调一次，可为 null
     */
    public ChatResponse streamAggregated(Prompt prompt, Consumer<String> reasoningDeltaConsumer) {
        OpenAiChatOptions requestOptions = mergeOptions(prompt.getOptions());
        ChatCompletionRequest request = createRequest(prompt.getInstructions(), requestOptions, true);

        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        Map<Integer, ToolCallAccumulator> toolCallsByIndex = new LinkedHashMap<>();
        StringBuilder finishReason = new StringBuilder();
        StringBuilder responseId = new StringBuilder();
        StringBuilder responseModel = new StringBuilder();

        this.openAiApi.chatCompletionStream(request).doOnNext(chunk -> {
            if (chunk.id() != null) {
                responseId.setLength(0);
                responseId.append(chunk.id());
            }
            if (chunk.model() != null) {
                responseModel.setLength(0);
                responseModel.append(chunk.model());
            }
            List<ChatCompletionChunk.ChunkChoice> choices = chunk.choices();
            if (CollectionUtils.isEmpty(choices)) {
                return; // 无内容帧（如 usage-only chunk）
            }
            ChatCompletionChunk.ChunkChoice choice = choices.get(0);
            ChatCompletionMessage delta = choice.delta();
            if (delta != null) {
                if (StringUtils.hasText(delta.reasoningContent())) {
                    reasoningBuilder.append(delta.reasoningContent());
                    if (reasoningDeltaConsumer != null) {
                        reasoningDeltaConsumer.accept(delta.reasoningContent());
                    }
                }
                if (StringUtils.hasText(delta.content())) {
                    contentBuilder.append(delta.content());
                }
                if (!CollectionUtils.isEmpty(delta.toolCalls())) {
                    mergeToolCallChunks(toolCallsByIndex, delta.toolCalls());
                }
            }
            if (choice.finishReason() != null) {
                finishReason.setLength(0);
                finishReason.append(choice.finishReason().name());
            }
        }).blockLast();

        return toAggregatedChatResponse(responseId.toString(), responseModel.toString(),
                finishReason.toString(), contentBuilder.toString(), reasoningBuilder.toString(),
                toolCallsByIndex);
    }

    /**
     * 合并流式 tool_call 分片：首片携带 id/type/name，后续片只带 arguments 增量，
     * 按 OpenAI 分片协议以 index 归并（arguments 为字符串拼接）。
     */
    private void mergeToolCallChunks(Map<Integer, ToolCallAccumulator> accumulator,
                                     List<ChatCompletionMessage.ToolCall> fragments) {
        for (ChatCompletionMessage.ToolCall fragment : fragments) {
            int index = fragment.index() != null ? fragment.index() : 0;
            ToolCallAccumulator acc = accumulator.computeIfAbsent(index, i -> new ToolCallAccumulator());
            if (fragment.id() != null) {
                acc.id = fragment.id();
            }
            if (fragment.type() != null) {
                acc.type = fragment.type();
            }
            if (fragment.function() != null) {
                if (fragment.function().name() != null) {
                    acc.name = fragment.function().name();
                }
                if (fragment.function().arguments() != null) {
                    acc.arguments.append(fragment.function().arguments());
                }
            }
        }
    }

    /** 流式 tool_call 分片的累加器，字段随 chunk 逐步补齐。 */
    private static final class ToolCallAccumulator {
        private String id = "";
        private String type = "function";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    /** 与 {@link #toChatResponse} 输出结构对齐，仅数据来源从整块响应换成流式聚合结果。 */
    private ChatResponse toAggregatedChatResponse(String id, String model, String finishReason,
                                                  String content, String reasoning,
                                                  Map<Integer, ToolCallAccumulator> toolCallsByIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", id);
        metadata.put("role", ChatCompletionMessage.Role.ASSISTANT.name());
        metadata.put("index", 0);
        metadata.put("finishReason", finishReason);
        metadata.put("refusal", "");
        metadata.put("annotations", List.of());
        metadata.put(REASONING_CONTENT_KEY, reasoning);

        List<AssistantMessage.ToolCall> toolCalls = toolCallsByIndex.values().stream()
                .map(acc -> new AssistantMessage.ToolCall(acc.id, acc.type, acc.name, acc.arguments.toString()))
                .toList();

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(content)
                .properties(metadata)
                .toolCalls(toolCalls)
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        ChatResponseMetadata responseMetadata = ChatResponseMetadata.builder()
                .id(id)
                .model(model)
                .build();
        return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)), responseMetadata);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return OpenAiChatOptions.fromOptions(this.defaultOptions);
    }

    /**
     * 以 yml 默认配置为底，叠加运行时 options（agent 传入的是 ToolCallingChatOptions，
     * 只携带工具相关字段；若是 OpenAiChatOptions 则按官方方式整体合并）。
     */
    private OpenAiChatOptions mergeOptions(ChatOptions runtimeOptions) {
        OpenAiChatOptions options = OpenAiChatOptions.fromOptions(this.defaultOptions);
        if (runtimeOptions instanceof OpenAiChatOptions openAiOptions) {
            options = ModelOptionsUtils.merge(openAiOptions, options, OpenAiChatOptions.class);
        }
        else if (runtimeOptions instanceof ToolCallingChatOptions toolOptions) {
            options.setToolCallbacks(toolOptions.getToolCallbacks());
            options.setToolNames(toolOptions.getToolNames());
            options.setToolContext(toolOptions.getToolContext());
            options.setInternalToolExecutionEnabled(toolOptions.getInternalToolExecutionEnabled());
            if (toolOptions.getModel() != null) {
                options.setModel(toolOptions.getModel());
            }
        }
        return options;
    }

    private ChatCompletionRequest createRequest(List<Message> instructions, OpenAiChatOptions requestOptions,
                                                boolean stream) {
        List<ChatCompletionMessage> messages = instructions.stream()
                .map(this::toApiMessages)
                .flatMap(List::stream)
                .toList();

        ChatCompletionRequest request = new ChatCompletionRequest(messages, stream);
        request = ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class);

        List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
        if (!CollectionUtils.isEmpty(toolDefinitions)) {
            request = ModelOptionsUtils.merge(OpenAiChatOptions.builder()
                    .tools(toFunctionTools(toolDefinitions))
                    .extraBody(request.extraBody())
                    .build(), request, ChatCompletionRequest.class);
        }
        return request;
    }

    /**
     * 与 {@link OpenAiChatModel} 的消息转换一致，唯一差异：ASSISTANT 消息把 metadata 中的
     * reasoningContent 回传为 {@code reasoning_content} 字段（DeepSeek thinking + tool call 的硬性要求）。
     */
    private List<ChatCompletionMessage> toApiMessages(Message message) {
        return switch (message.getMessageType()) {
            case USER, SYSTEM -> List.of(new ChatCompletionMessage(message.getText(),
                    ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
            case ASSISTANT -> {
                var assistantMessage = (AssistantMessage) message;
                List<ChatCompletionMessage.ToolCall> toolCalls = null;
                if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                    toolCalls = assistantMessage.getToolCalls().stream()
                            .map(tc -> new ChatCompletionMessage.ToolCall(tc.id(), tc.type(),
                                    new ChatCompletionFunction(tc.name(), tc.arguments())))
                            .toList();
                }
                Object reasoning = assistantMessage.getMetadata().get(REASONING_CONTENT_KEY);
                yield List.of(new ChatCompletionMessage(assistantMessage.getText(),
                        ChatCompletionMessage.Role.ASSISTANT, null, null, toolCalls, null, null, null,
                        reasoning instanceof String s ? s : null));
            }
            case TOOL -> {
                var toolMessage = (ToolResponseMessage) message;
                toolMessage.getResponses()
                        .forEach(response -> Assert.isTrue(response.id() != null,
                                "ToolResponseMessage must have an id"));
                yield toolMessage.getResponses().stream()
                        .map(tr -> new ChatCompletionMessage(tr.responseData(),
                                ChatCompletionMessage.Role.TOOL, tr.name(), tr.id(), null, null, null, null, null))
                        .toList();
            }
        };
    }

    private List<FunctionTool> toFunctionTools(List<ToolDefinition> toolDefinitions) {
        return toolDefinitions.stream()
                .map(td -> new FunctionTool(
                        new FunctionTool.Function(td.description(), td.name(), td.inputSchema())))
                .toList();
    }

    private ChatResponse toChatResponse(ChatCompletion chatCompletion) {
        List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("id", chatCompletion.id() != null ? chatCompletion.id() : "");
            metadata.put("role", choice.message().role() != null ? choice.message().role().name() : "");
            metadata.put("index", choice.index() != null ? choice.index() : 0);
            metadata.put("finishReason", choice.finishReason() != null ? choice.finishReason().name() : "");
            metadata.put("refusal",
                    StringUtils.hasText(choice.message().refusal()) ? choice.message().refusal() : "");
            metadata.put("annotations",
                    choice.message().annotations() != null ? choice.message().annotations() : List.of());
            metadata.put(REASONING_CONTENT_KEY,
                    choice.message().reasoningContent() != null ? choice.message().reasoningContent() : "");

            List<AssistantMessage.ToolCall> toolCalls = choice.message().toolCalls() == null ? List.of()
                    : choice.message().toolCalls().stream()
                            .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function",
                                    tc.function().name(), tc.function().arguments()))
                            .toList();

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content(choice.message().content())
                    .properties(metadata)
                    .toolCalls(toolCalls)
                    .build();
            ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                    .finishReason((String) metadata.get("finishReason"))
                    .build();
            return new Generation(assistantMessage, generationMetadata);
        }).toList();

        ChatResponseMetadata responseMetadata = ChatResponseMetadata.builder()
                .id(chatCompletion.id() != null ? chatCompletion.id() : "")
                .model(chatCompletion.model() != null ? chatCompletion.model() : "")
                .build();
        return new ChatResponse(generations, responseMetadata);
    }
}
