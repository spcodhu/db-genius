package com.dbgenius.agent.intent;

import com.dbgenius.agent.stream.SseChannel;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.IntentClassificationResult;

import java.util.Locale;

/**
 * 意图处理器接口
 */
public interface IntentHandler {

    /**
     * 支持的意图类型
     */
    IntentType supportedIntent();

    /**
     * 处理该意图的请求。
     * Handler 复用 Router 传入的 {@link SseChannel} 和 taskId，保证 SSE 连接、事件追踪
     * 与「客户端已断开」状态在整条链路内一致。
     *
     * @param tokenUsage 本轮请求的 token 用量累加器（Router 创建，已含分类调用用量）
     * @param locale     本轮请求的语言环境（异步线程中 LocaleContextHolder 已失效，必须显式传入）
     */
    void handle(SseChannel channel, String taskId, UnifiedChatRequest request,
                IntentClassificationResult classification, Long userId,
                TokenUsageAccumulator tokenUsage, Locale locale);
}
