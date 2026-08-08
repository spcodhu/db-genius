package com.dbgenius.agent.usage;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * ChatModel 装饰器：在官方 {@link org.springframework.ai.openai.OpenAiChatModel}
 * 外层记录每次调用的 token 用量到 {@link TokenUsageAccumulator}。
 *
 * <p>覆盖 simple chat 流式与意图分类等 call() 路径；Agent 工具调用路径由
 * {@link com.dbgenius.agent.ReasoningChatModel} 自行记账，不经过本类。
 */
public class UsageTrackingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageAccumulator accumulator;

    public UsageTrackingChatModel(ChatModel delegate, TokenUsageAccumulator accumulator) {
        this.delegate = delegate;
        this.accumulator = accumulator;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        record(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 开启 streamUsage 后，末尾 usage-only chunk 的 metadata 携带用量；逐帧检查，空用量忽略
        return delegate.stream(prompt).doOnNext(this::record);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void record(ChatResponse response) {
        if (response == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage != null && usage.getTotalTokens() > 0) {
            accumulator.add(usage);
        }
    }
}
