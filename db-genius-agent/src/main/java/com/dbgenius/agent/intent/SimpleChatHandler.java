package com.dbgenius.agent.intent;

import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 简单会话问答处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimpleChatHandler implements IntentHandler {

    private final ChatClient chatClient;
    private final ConversationService conversationService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public IntentType supportedIntent() {
        return IntentType.SIMPLE_CHAT;
    }

    @Override
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId) {
        ConversationVO conversation = getOrCreateConversation(userId, request);
        conversationService.saveMessage(conversation.getId(), "user", request.getMessage(), null, "user");

        StringBuilder fullContent = new StringBuilder();

        Disposable disposable = chatClient.prompt()
                .user(request.getMessage())
                .stream()
                .content()
                .subscribe(
                        token -> {
                            fullContent.append(token);
                            sendEvent(emitter, SseEvent.of(taskId, 0, "content", token));
                        },
                        error -> {
                            log.error("[SimpleChatHandler] Stream error", error);
                            sendEvent(emitter, SseEvent.error(taskId, error.getMessage()));
                            emitter.complete();
                        },
                        () -> {
                            conversationService.saveMessage(
                                    conversation.getId(), "assistant", fullContent.toString(), -1, "summary");
                            sendEvent(emitter, SseEvent.done(taskId));
                            emitter.complete();
                        }
                );

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);
        emitter.onError(e -> disposable.dispose());
    }

    private ConversationVO getOrCreateConversation(Long userId, UnifiedChatRequest request) {
        Long conversationId = request.getConversationId();
        if (conversationId != null) {
            Conversation existing = conversationService.getById(conversationId);
            if (existing != null && existing.getUserId().equals(userId)) {
                ConversationVO vo = new ConversationVO();
                vo.setId(existing.getId());
                vo.setTitle(existing.getTitle());
                vo.setType(existing.getType());
                return vo;
            }
        }
        return conversationService.createConversation(
                userId, request.getMessage(), IntentType.SIMPLE_CHAT.getCode(), null);
    }

    private void sendEvent(SseEmitter emitter, SseEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.warn("[SimpleChatHandler] Failed to send SSE event: {}", e.getMessage());
        }
    }
}
