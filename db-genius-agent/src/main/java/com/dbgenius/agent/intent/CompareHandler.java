package com.dbgenius.agent.intent;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.agent.DbCompareAgent;
import com.dbgenius.agent.ReasoningChatModel;
import com.dbgenius.agent.ToolCallAgent;
import com.dbgenius.agent.tool.DbCompareTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.UserModelConfigService;
import com.dbgenius.trial.TrialDeny;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 数据库对比处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompareHandler implements IntentHandler {

    private final ChatModelFactory chatModelFactory;
    private final UserModelConfigService userModelConfigService;
    private final Executor chatTaskExecutor;
    private final DbConfigService dbConfigService;
    private final ConversationService conversationService;
    private final DbCompareTool dbCompareTool;
    private final SqlExecuteTool sqlExecuteTool;
    private final TerminateTool terminateTool;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int HISTORY_SIZE = 10;

    @Override
    public IntentType supportedIntent() {
        return IntentType.DB_COMPARE;
    }

    @Override
    @TrialDeny("试用版暂不支持数据库对比")
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId) {
        Long preDbConfigId = request.getPreDbConfigId();
        Long testDbConfigId = request.getTestDbConfigId();
        if (preDbConfigId == null || testDbConfigId == null) {
            throw new IllegalArgumentException("数据库对比需要同时提供 preDbConfigId 和 testDbConfigId");
        }

        dbConfigService.validateConfigForChat(userId, preDbConfigId);
        dbConfigService.validateConfigForChat(userId, testDbConfigId);

        DbConfig preConfig = dbConfigService.getById(preDbConfigId);
        DbConfig testConfig = dbConfigService.getById(testDbConfigId);

        String preDbDoc = preConfig != null && preConfig.getDocContent() != null
                ? preConfig.getDocContent() : "Documentation not available";
        String testDbDoc = testConfig != null && testConfig.getDocContent() != null
                ? testConfig.getDocContent() : "Documentation not available";

        List<Long> configIds = List.of(preDbConfigId, testDbConfigId);
        ConversationVO conversation = getOrCreateConversation(userId, request, configIds);
        sendEvent(emitter, SseEvent.of(taskId, 0, "conversation", conversation.getId()));

        String message = request.getMessage() != null ? request.getMessage()
                : "Please compare the pre and test databases and generate deployment SQL.";

        // 先取历史再保存本轮用户消息，避免把当前消息重复塞进历史
        List<org.springframework.ai.chat.messages.Message> historyMessages =
                toHistoryMessages(conversationService.getRecentMessages(conversation.getId(), HISTORY_SIZE));
        conversationService.saveMessage(conversation.getId(), "user", message, null, "user");

        ChatModelSession session = chatModelFactory.createSession(
                userModelConfigService.getActiveConfig(userId));

        DbCompareAgent agent = new DbCompareAgent(
                session.agentChatClient(), session.reasoningModel(), dbCompareTool, sqlExecuteTool, terminateTool, preDbDoc, testDbDoc);
        agent.setHistoryMessages(historyMessages);
        agent.setExecutor(chatTaskExecutor);
        agent.setSummaryCallback(markdown ->
                conversationService.saveMessage(conversation.getId(), "assistant", markdown, -1, "summary"));
        agent.setMessageSink(new ToolCallAgent.AgentMessageSink() {
            @Override
            public void onAssistant(int step, String content, String reasoningContent, String toolCallsJson) {
                conversationService.saveMessage(conversation.getId(), "assistant", content, step, "step",
                        reasoningContent, toolCallsJson);
            }

            @Override
            public void onToolResponses(int step, String toolResponsesJson) {
                if (toolResponsesJson != null) {
                    conversationService.saveMessage(conversation.getId(), "tool", toolResponsesJson, step, "tool");
                }
            }
        });
        agent.runStream(message, taskId, emitter);
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
            log.warn("[CompareHandler] Failed to send SSE event: {}", e.getMessage());
        }
    }

    private ConversationVO getOrCreateConversation(Long userId, UnifiedChatRequest request, List<Long> dbConfigIds) {
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
        String configIdsStr = dbConfigIds.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        return conversationService.createConversation(
                userId, request.getMessage(), IntentType.DB_COMPARE.getCode(), configIdsStr);
    }
}
