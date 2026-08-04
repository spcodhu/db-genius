package com.dbgenius.agent;

import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.UserModelConfig;
import org.springframework.ai.chat.client.ChatClient;
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
     * @return ChatModelSession，包含 chatClient + agentChatClient + 底层模型引用
     */
    public ChatModelSession createSession(UserModelConfig config) {
        String apiKey = resolveApiKey(config);
        String baseUrl = config.getBaseUrl();
        String modelName = config.getModelName();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(modelName)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .toolCallingManager(toolCallingManager)
                .defaultOptions(defaultOptions)
                .build();

        // 构造 ReasoningChatModel 需要的 OpenAiChatProperties（仅传必要的默认 options）
        OpenAiChatProperties chatProperties = new OpenAiChatProperties();
        chatProperties.getOptions().setModel(modelName);

        ReasoningChatModel reasoningModel = new ReasoningChatModel(
                openAiApi, chatModel, chatProperties, toolCallingManager);

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        ChatClient agentChatClient = ChatClient.builder(reasoningModel).build();

        return new ChatModelSession(chatModel, reasoningModel, chatClient, agentChatClient);
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
