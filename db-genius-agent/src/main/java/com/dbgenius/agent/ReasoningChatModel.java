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
import java.util.List;
import java.util.Map;

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
        ChatCompletionRequest request = createRequest(prompt.getInstructions(), requestOptions);
        ChatCompletion chatCompletion = this.openAiApi.chatCompletionEntity(request).getBody();
        Assert.notNull(chatCompletion, "chat completion must not be null");
        return toChatResponse(chatCompletion);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return this.delegate.stream(prompt);
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

    private ChatCompletionRequest createRequest(List<Message> instructions, OpenAiChatOptions requestOptions) {
        List<ChatCompletionMessage> messages = instructions.stream()
                .map(this::toApiMessages)
                .flatMap(List::stream)
                .toList();

        ChatCompletionRequest request = new ChatCompletionRequest(messages, false);
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
