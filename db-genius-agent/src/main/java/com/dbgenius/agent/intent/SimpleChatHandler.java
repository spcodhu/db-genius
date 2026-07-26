package com.dbgenius.agent.intent;

import com.dbgenius.agent.ReasoningChatModel;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
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

    private static final int HISTORY_SIZE = 10;

    @Override
    public IntentType supportedIntent() {
        return IntentType.SIMPLE_CHAT;
    }

    /**
     * 处理简单会话：以 SSE 流式方式把大模型回复逐段推给前端，并落库保存对话。
     *
     * <p>核心是 chatClient 的流式调用链，各环节含义如下：
     * <ul>
     *   <li>{@code prompt()} —— 开启一次请求构建（fluent API 的入口）。</li>
     *   <li>{@code user(...)} —— 设置本轮用户消息内容。</li>
     *   <li>{@code stream()} —— 声明使用流式响应，返回值基于 Reactor，
     *       模型会边生成边推送，而非等全部生成完再一次性返回。</li>
     *   <li>{@code chatResponse()} —— 获取完整的流式响应（含元数据），用于同时提取
     *       正文 token 与 thinking 模式的推理增量（{@code reasoningContent}）。</li>
     *   <li>{@code subscribe(onNext, onError, onComplete)} —— 订阅该流，真正触发调用；
     *       三个回调分别处理：收到每段 token、发生错误、流正常结束。
     *       返回 {@link reactor.core.Disposable}，用于在需要时取消订阅、释放底层连接。</li>
     * </ul>
     *
     * <p>处理流程：
     * <ol>
     *   <li>每收到一个 chunk：推理增量推送 {@code reasoning} 事件；正文 token 累加到
     *       {@code fullContent} 并推送 {@code content} 事件。</li>
     *   <li>出错：推送 error 事件并结束 emitter。</li>
     *   <li>完成：把完整回复作为 assistant 消息落库，推送 done 事件并结束 emitter。</li>
     * </ol>
     *
     * <p>最后的 {@code emitter.onCompletion/onTimeout/onError} 是 SSE 连接的生命周期回调：
     * 无论正常结束、超时还是异常，都调用 {@code disposable.dispose()} 取消上游订阅，
     * 避免客户端断开后模型流仍在后台空转造成资源泄漏。
     *
     * @param emitter        SSE 发射器，用于向前端持续推送事件
     * @param taskId         本次任务标识，随每个 SSE 事件下发
     * @param request        统一聊天请求（含消息、会话 ID 等）
     * @param classification 意图分类结果（此处已确定为 SIMPLE_CHAT）
     * @param userId         当前用户 ID，用于会话归属校验
     */
    @Override
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId) {
        ConversationVO conversation = getOrCreateConversation(userId, request);
        sendEvent(emitter, SseEvent.of(taskId, 0, "conversation", conversation.getId()));

        // 先取历史再保存本轮用户消息，避免把当前消息重复塞进历史
        List<Message> history = conversationService.getRecentMessages(conversation.getId(), HISTORY_SIZE);
        List<org.springframework.ai.chat.messages.Message> historyMessages = toHistoryMessages(history);

        conversationService.saveMessage(conversation.getId(), "user", request.getMessage(), null, "user");

        StringBuilder fullContent = new StringBuilder();

        Disposable disposable = chatClient.prompt()
                .messages(historyMessages)
                .user(request.getMessage())
                .stream()
                .chatResponse()
                .subscribe(
                        chatResponse -> {
                            if (chatResponse.getResult() == null) {
                                return;
                            }
                            var output = chatResponse.getResult().getOutput();
                            // thinking 模式的推理增量，与正文分通道推送
                            Object reasoning = output.getMetadata()
                                    .get(ReasoningChatModel.REASONING_CONTENT_KEY);
                            if (reasoning instanceof String reasoningDelta && !reasoningDelta.isEmpty()) {
                                sendEvent(emitter, SseEvent.of(taskId, 0, "reasoning", reasoningDelta));
                            }
                            String token = output.getText();
                            if (token != null && !token.isEmpty()) {
                                fullContent.append(token);
                                sendEvent(emitter, SseEvent.of(taskId, 0, "content", token));
                            }
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

        // 类似于 finally ，在流结束之后取消之前的订阅
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);
        emitter.onError(e -> {
            log.error("[SimpleChatHandler] SSE emitter error", e);
            disposable.dispose();
        });
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

    private List<org.springframework.ai.chat.messages.Message> toHistoryMessages(List<Message> history) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (Message message : history) {
            if ("user".equals(message.getRole())) {
                messages.add(new UserMessage(message.getContent()));
            } else if ("assistant".equals(message.getRole())) {
                messages.add(new AssistantMessage(message.getContent()));
            }
        }
        return messages;
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
