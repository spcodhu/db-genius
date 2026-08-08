package com.dbgenius.agent.usage;

import com.dbgenius.model.vo.TokenUsageVO;
import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮会话请求的 token 用量累加器。
 *
 * <p>一轮请求包含多次 LLM 调用（意图分类、Agent 多步 think、summary、简单会话流式），
 * 累加器在 {@link com.dbgenius.intent.IntentRouter}（路由入口）创建，随请求下传给
 * 分类器与各 Handler，由 {@link UsageTrackingChatModel} 与
 * {@link com.dbgenius.agent.ReasoningChatModel} 在每次调用后记账。
 *
 * <p>上下文占用口径：{@code lastPromptTokens} 取最后一次调用的 prompt_tokens，
 * 即供应商对实际发出完整 prompt 的权威计数。
 */
public class TokenUsageAccumulator {

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicInteger callCount = new AtomicInteger();

    /** 最后一次调用的 prompt_tokens，作为当前上下文窗口占用 */
    private volatile int lastPromptTokens;

    /** 当前模型最大上下文窗口，由 ChatModelFactory 从用户配置注入，未知为 null */
    private volatile Integer contextWindow;

    /** 持久化后的会话累计消耗，由调用方在落库后回填 */
    private volatile Long conversationTotalTokens;

    /** 记录一次 LLM 调用的用量；空用量（totalTokens<=0）忽略，防重复计账。 */
    public void add(Usage usage) {
        if (usage == null || usage.getTotalTokens() <= 0) {
            return;
        }
        promptTokens.addAndGet(usage.getPromptTokens());
        completionTokens.addAndGet(usage.getCompletionTokens());
        totalTokens.addAndGet(usage.getTotalTokens());
        callCount.incrementAndGet();
        lastPromptTokens = (int) Math.min(usage.getPromptTokens(), Integer.MAX_VALUE);
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public void setConversationTotalTokens(Long conversationTotalTokens) {
        this.conversationTotalTokens = conversationTotalTokens;
    }

    public int getCallCount() {
        return callCount.get();
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    /** 生成当前快照，用于 SSE usage 事件与持久化。 */
    public TokenUsageVO snapshot() {
        return TokenUsageVO.builder()
                .promptTokens(promptTokens.get())
                .completionTokens(completionTokens.get())
                .totalTokens(totalTokens.get())
                .contextTokens(lastPromptTokens)
                .callCount(callCount.get())
                .conversationTotalTokens(conversationTotalTokens)
                .contextWindow(contextWindow)
                .build();
    }
}
