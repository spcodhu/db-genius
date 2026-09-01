package com.dbgenius.agent;

import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.agent.usage.UsageTrackingChatModel;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.UserModelConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
    /** 容器级 ObservationRegistry（actuator 提供）；不传进模型时默认为 NOOP，observation 静默丢弃 */
    private final ObservationRegistry observationRegistry;
    /** 业务指标注册表：转发给 ReasoningChatModel 记录 TTFT */
    private final MeterRegistry meterRegistry;

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    /**
     * Tier-2 单轮内瘦身：只回传最后一条 assistant 消息的 reasoning_content。
     * 收益很大（单轮多步 ReAct 中历史 reasoning 是上下文大头），但各供应商对缺失历史
     * reasoning 的容忍度不一（可能 400），因此默认关闭、灰度打开。
     */
    @Value("${db-genius.context.in-run.drop-stale-reasoning.enabled:false}")
    private boolean dropStaleReasoning;

    public ChatModelFactory(RestClient.Builder restClientBuilder,
                            WebClient.Builder webClientBuilder,
                            ToolCallingManager toolCallingManager,
                            ObservationRegistry observationRegistry,
                            MeterRegistry meterRegistry) {
        this.restClientBuilder = restClientBuilder;
        this.webClientBuilder = webClientBuilder;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
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
                // 不传 registry 时模型默认 NOOP，Spring AI 自带的 gen_ai.client.* 埋点静默丢弃
                .observationRegistry(observationRegistry)
                .build();

        // 构造 ReasoningChatModel 需要的 OpenAiChatProperties（仅传必要的默认 options）
        OpenAiChatProperties chatProperties = new OpenAiChatProperties();
        chatProperties.getOptions().setModel(modelName);
        chatProperties.getOptions().setStreamUsage(true);

        ReasoningChatModel reasoningModel = new ReasoningChatModel(
                openAiApi, chatModel, chatProperties, toolCallingManager);
        reasoningModel.setDropStaleReasoning(dropStaleReasoning);
        // 断点 3：Agent 热路径绕过 OpenAiChatModel 直连 API，Spring AI 埋点不生效，
        // 观测由 ReasoningChatModel 内部手写 Observation 补齐（见该类注释）
        reasoningModel.setObservationRegistry(observationRegistry);
        reasoningModel.setMeterRegistry(meterRegistry);
        reasoningModel.setGenAiSystem(resolveGenAiSystem(config));

        ChatModel effectiveChatModel = chatModel;
        if (accumulator != null) {
            reasoningModel.setTokenUsageAccumulator(accumulator);
            accumulator.setContextWindow(config.getContextWindow());
            effectiveChatModel = new UsageTrackingChatModel(chatModel, accumulator);
        }

        ChatClient chatClient = ChatClient.builder(effectiveChatModel).build();

        return new ChatModelSession(effectiveChatModel, reasoningModel, chatClient);
    }

    /**
     * gen_ai.system 属性取值：供应商 code（deepseek/openai/ollama/system 等，枚举集合有界，
     * 符合 metric tag 基数纪律）；缺省 "unknown"。
     */
    private String resolveGenAiSystem(UserModelConfig config) {
        return StringUtils.hasText(config.getProviderCode()) ? config.getProviderCode() : "unknown";
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
