package com.dbgenius.agent;

import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.agent.usage.UsageTrackingChatModel;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.UserModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 动态 ChatModel 工厂。
 *
 * <p>替代原先 {@code ChatClientConfig} 中的两个静态单例 Bean，
 * 根据用户选定的模型配置在运行时动态创建 {@link OpenAiApi} →
 * {@link OpenAiChatModel} + {@link ReasoningChatModel} 的组合。
 *
 * <p>每个{@link #createSession} 调用产生独立的模型实例，互不干扰。
 * 对于系统级 fallback（providerCode = "system"），apiKey 为明文
 * 存储，无需解密。
 */
@Component
public class ChatModelFactory {

    private final RestClient.Builder restClientBuilder;
    private final WebClient.Builder webClientBuilder;
    private final ToolCallingManager toolCallingManager;

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    public ChatModelFactory(RestClient.Builder restClientBuilder,
                            WebClient.Builder webClientBuilder,
                            ToolCallingManager toolCallingManager) {
        this.restClientBuilder = restClientBuilder;
        this.webClientBuilder = webClientBuilder;
        this.toolCallingManager = toolCallingManager;
    }

    /**
     * 根据用户模型配置创建一个完整的会话模型组合。
     *
     * @param config 用户配置（含 apiKey；如果是 fallback，"system" provider 的 key 为明文）
     * @return ChatModelSession，包含 chatClient + 底层模型引用
     */
    public ChatModelSession createSession(UserModelConfig config) {
        return createSession(config, null);
    }

    /**
     * 带 token 用量累加器的重载：两条 LLM 调用路径（官方模型 + ReasoningChatModel）
     * 的每次调用都会记账到累加器，并把配置的上下文窗口注入累加器供占用计算。
     *
     * @param accumulator 本轮请求的用量累加器，null 表示不统计
     */
    public ChatModelSession createSession(UserModelConfig config, TokenUsageAccumulator accumulator) {
        String apiKey = resolveApiKey(config);
        String baseUrl = config.getBaseUrl();
        String modelName = config.getModelName();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        // streamUsage：请求携带 stream_options.include_usage，流式末尾帧返回用量
        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(modelName)
                .streamUsage(true)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .toolCallingManager(toolCallingManager)
                .defaultOptions(defaultOptions)
                .build();

        // 构造 ReasoningChatModel 需要的 OpenAiChatProperties（仅传必要的默认 options）
        OpenAiChatProperties chatProperties = new OpenAiChatProperties();
        chatProperties.getOptions().setModel(modelName);
        chatProperties.getOptions().setStreamUsage(true);

        ReasoningChatModel reasoningModel = new ReasoningChatModel(
                openAiApi, chatModel, chatProperties, toolCallingManager);

        ChatModel effectiveChatModel = chatModel;
        if (accumulator != null) {
            reasoningModel.setTokenUsageAccumulator(accumulator);
            accumulator.setContextWindow(config.getContextWindow());
            effectiveChatModel = new UsageTrackingChatModel(chatModel, accumulator);
        }

        ChatClient chatClient = ChatClient.builder(effectiveChatModel).build();

        return new ChatModelSession(effectiveChatModel, reasoningModel, chatClient);
    }

    private String resolveApiKey(UserModelConfig config) {
        if ("system".equals(config.getProviderCode())) {
            // fallback 配置的 apiKey 为明文
            return config.getApiKeyEncrypted();
        }
        try {
            return AesUtil.decrypt(config.getApiKeyEncrypted(), encryptKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key for model config id=" + config.getId(), e);
        }
    }
}
