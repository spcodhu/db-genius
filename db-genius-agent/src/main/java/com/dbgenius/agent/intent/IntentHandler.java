package com.dbgenius.agent.intent;

import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.IntentClassificationResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
     * Handler 复用 Router 传入的 SseEmitter 和 taskId，保证 SSE 连接与事件追踪一致。
     *
     * @param tokenUsage 本轮请求的 token 用量累加器（Router 创建，已含分类调用用量）
     */
    void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                IntentClassificationResult classification, Long userId, TokenUsageAccumulator tokenUsage);
}
