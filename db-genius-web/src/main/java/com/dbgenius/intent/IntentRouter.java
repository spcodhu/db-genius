package com.dbgenius.intent;

import com.dbgenius.agent.compress.AutoCompressService;
import com.dbgenius.agent.intent.ChatContext;
import com.dbgenius.agent.intent.IntentClassifier;
import com.dbgenius.agent.intent.IntentHandler;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 意图路由编排器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final IntentClassifier classifier;
    private final IntentHandlerRegistry registry;
    private final ConversationService conversationService;
    private final Executor chatTaskExecutor;
    private final AutoCompressService autoCompressService;

    private static final int HISTORY_CONTEXT_SIZE = 5;
    private static final double CONFIDENCE_THRESHOLD = IntentClassifier.CONFIDENCE_THRESHOLD;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter route(UnifiedChatRequest request, Long userId) {
        String taskId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(300_000L);
        // 本轮请求的 token 用量累加器：分类与各 Handler 的 LLM 调用统一记账
        TokenUsageAccumulator tokenUsage = new TokenUsageAccumulator();

        CompletableFuture.runAsync(() -> {
            try {
                // 1. 如果用户已确认意图，跳过分类
                if (request.getConfirmedIntent() != null) {
                    IntentClassificationResult confirmed = new IntentClassificationResult(
                            request.getConfirmedIntent(), 1.0, "User confirmed intent", false);
                    sendEvent(emitter, SseEvent.of(taskId, 0, "routing",
                            "正在启动" + confirmed.intent().getDescription() + "..."));
                    dispatchToHandler(emitter, taskId, request, confirmed, userId, tokenUsage);
                    return;
                }

                // 2. 推送 classifying 状态
                sendEvent(emitter, SseEvent.of(taskId, 0, "classifying", "正在分析您的问题..."));

                // 3. 加载历史上下文
                List<Message> history = loadRecentHistory(request.getConversationId());

                // 3.1 自动压缩钩子（默认关闭；开源版和正式版环境变量里面都已经使用环境变量打开）
                autoCompressService.compressIfNeeded(request.getConversationId(), userId);

                // 4. 构建分类上下文
                ChatContext context = buildChatContext(request);

                // 5. LLM 分类
                IntentClassificationResult result = classifier.classify(
                        request.getMessage(), history, context, userId, tokenUsage);

                log.info("[IntentRouter] classified intent={}, confidence={}, needsClarification={}",
                        result.intent(), result.confidence(), result.needsClarification());

                // 6. 推送分类结果
                sendEvent(emitter, SseEvent.of(taskId, 0, "classified", result));

                // 7. 判断是否需要确认
                if (result.needsClarification() || result.confidence() < CONFIDENCE_THRESHOLD) {
                    sendClarificationEvent(emitter, taskId, result, request);
                    // 澄清分支不进入 Handler，分类消耗的 token 也需告知前端
                    sendUsageEvent(emitter, taskId, tokenUsage);
                    return;
                }

                // 8. 路由到 Handler
                sendEvent(emitter, SseEvent.of(taskId, 0, "routing",
                        "正在启动" + result.intent().getDescription() + "..."));
                dispatchToHandler(emitter, taskId, request, result, userId, tokenUsage);

            } catch (Exception e) {
                log.error("[IntentRouter] Route error", e);
                sendUsageEvent(emitter, taskId, tokenUsage);
                sendEvent(emitter, SseEvent.error(taskId, e.getMessage()));
                emitter.complete();
            }
        }, chatTaskExecutor);

        return emitter;
    }

    private void dispatchToHandler(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                                   IntentClassificationResult classification, Long userId,
                                   TokenUsageAccumulator tokenUsage) {
        IntentHandler handler = registry.getHandler(classification.intent());
        try {
            handler.handle(emitter, taskId, request, classification, userId, tokenUsage);
        } catch (Exception e) {
            log.error("[IntentRouter] Handler error", e);
            sendUsageEvent(emitter, taskId, tokenUsage);
            sendEvent(emitter, SseEvent.error(taskId, e.getMessage()));
            emitter.complete();
        }
    }

    /** 尽力而为地下发已记账的用量（无调用则不发） */
    private void sendUsageEvent(SseEmitter emitter, String taskId, TokenUsageAccumulator tokenUsage) {
        if (tokenUsage == null || tokenUsage.getCallCount() == 0) {
            return;
        }
        sendEvent(emitter, SseEvent.of(taskId, -1, "usage", tokenUsage.snapshot()));
    }

    private void sendClarificationEvent(SseEmitter emitter, String taskId,
                                        IntentClassificationResult result, UnifiedChatRequest request) {
        Map<String, Object> clarifyContent = new LinkedHashMap<>();
        clarifyContent.put("question", "我不太确定您的意图，请选择：");
        clarifyContent.put("options", buildOptions(result, request));
        clarifyContent.put("reasoning", result.reasoning());
        sendEvent(emitter, SseEvent.of(taskId, 0, "clarify", clarifyContent));
        emitter.complete();
    }

    private List<Map<String, String>> buildOptions(IntentClassificationResult result, UnifiedChatRequest request) {
        List<Map<String, String>> options = new ArrayList<>();

        IntentType primary = result.intent() != null ? result.intent() : IntentType.SIMPLE_CHAT;
        options.add(Map.of(
                "intent", primary.getCode(),
                "label", primary.getDescription()
        ));

        // 当主要意图不是简单会话时，提供降级为简单会话的选项
        if (primary != IntentType.SIMPLE_CHAT) {
            options.add(Map.of(
                    "intent", IntentType.SIMPLE_CHAT.getCode(),
                    "label", "只是简单询问/闲聊"
            ));
        }

        // 如果缺少前置条件，给出提示性选项
        ChatContext context = buildChatContext(request);
        if (primary == IntentType.SQL_QUERY && !context.hasDbConfig()) {
            options.set(0, Map.of(
                    "intent", IntentType.SQL_QUERY.getCode(),
                    "label", "查询数据库（需要选择数据库）"
            ));
        }
        if (primary == IntentType.DB_COMPARE && !context.hasCompareConfig()) {
            options.set(0, Map.of(
                    "intent", IntentType.DB_COMPARE.getCode(),
                    "label", "对比两个数据库（需要提供 pre/test 数据库）"
            ));
        }

        return options;
    }

    private List<Message> loadRecentHistory(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        return conversationService.getRecentMessages(conversationId, HISTORY_CONTEXT_SIZE);
    }

    private ChatContext buildChatContext(UnifiedChatRequest request) {
        boolean hasDbConfig = request.getDbConfigIds() != null && !request.getDbConfigIds().isEmpty();
        boolean hasFiles = request.getFileIds() != null && !request.getFileIds().isEmpty();
        boolean hasCompareConfig = request.getPreDbConfigId() != null && request.getTestDbConfigId() != null;
        return new ChatContext(hasDbConfig, hasFiles, hasCompareConfig);
    }

    private void sendEvent(SseEmitter emitter, SseEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.warn("[IntentRouter] Failed to send SSE event: {}", e.getMessage());
        }
    }
}
