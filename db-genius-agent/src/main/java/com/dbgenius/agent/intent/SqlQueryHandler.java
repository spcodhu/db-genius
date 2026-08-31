package com.dbgenius.agent.intent;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.agent.DbSqlAgent;
import com.dbgenius.agent.ReasoningChatModel;
import com.dbgenius.agent.ToolCallAgent;
import com.dbgenius.agent.compress.ObservationElider;
import com.dbgenius.agent.guard.LoopBreakerFactory;
import com.dbgenius.agent.compress.StepHistoryCondenser;
import com.dbgenius.agent.stream.SseChannel;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.agent.tool.ToolOutputReadTool;
import com.dbgenius.agent.tool.guard.ToolOutputArtifactStore;
import com.dbgenius.agent.tool.guard.ToolOutputGuard;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.i18n.MessageService;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.enums.DbType;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.UserModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * SQL 对话查询处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlQueryHandler implements IntentHandler {

    private final ChatModelFactory chatModelFactory;
    private final UserModelConfigService userModelConfigService;
    private final Executor chatTaskExecutor;
    private final DbConfigService dbConfigService;
    private final ConversationService conversationService;
    private final SqlExecuteTool sqlExecuteTool;
    private final TerminateTool terminateTool;
    private final ToolOutputReadTool toolOutputReadTool;
    private final ToolOutputGuard toolOutputGuard;
    private final StepHistoryCondenser stepHistoryCondenser;
    private final ObservationElider observationElider;
    private final LoopBreakerFactory loopBreakerFactory;
    private final MessageService messageService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int HISTORY_SIZE = 10;

    @Override
    public IntentType supportedIntent() {
        return IntentType.SQL_QUERY;
    }

    @Override
    public void handle(SseChannel channel, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId,
                       TokenUsageAccumulator tokenUsage, Locale locale) {
        List<Long> dbConfigIds = request.getDbConfigIds();

        // 先建会话并下发 conversation 事件、落库本轮用户消息，保证即使后续前置校验失败，
        // 会话与首条消息也已持久化，客户端重试可携带会话 id 复用会话、恢复上下文
        ConversationVO conversation = getOrCreateConversation(userId, request,
                dbConfigIds == null || dbConfigIds.isEmpty() ? List.of() : dbConfigIds);
        channel.send(SseEvent.of(taskId, 0, "conversation", conversation.getId()));

        // 先取历史再保存本轮用户消息，避免把当前消息重复塞进历史
        List<org.springframework.ai.chat.messages.Message> historyMessages =
                toHistoryMessages(conversationService.getRecentMessages(conversation.getId(), HISTORY_SIZE));
        conversationService.saveMessage(conversation.getId(), "user", request.getMessage(), null, "user");

        if (dbConfigIds == null || dbConfigIds.isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_QUERY_NO_DB_CONFIG);
        }

        validateDbConfigs(userId, dbConfigIds);
        String dbDoc = buildDbDocContext(userId, dbConfigIds);
        String dialectContext = buildDialectContext(dbConfigIds);

        ChatModelSession session = chatModelFactory.createSession(
                userModelConfigService.getActiveConfig(userId), tokenUsage);

        DbSqlAgent agent = new DbSqlAgent(session.reasoningModel(), sqlExecuteTool, terminateTool,
                toolOutputReadTool, dbDoc, dialectContext,
                Map.of(ToolOutputArtifactStore.CONTEXT_TASK_ID, taskId), locale);
        agent.setMessageService(messageService);
        agent.setHistoryMessages(historyMessages);
        agent.setExecutor(chatTaskExecutor);
        agent.setSummaryCallback(markdown ->
                conversationService.saveMessage(conversation.getId(), "assistant", markdown, -1, "summary"));
        agent.setTokenUsageAccumulator(tokenUsage);
        agent.setUsageCallback(usageVO -> {
            long newTotal = conversationService.updateTokenUsage(
                    conversation.getId(), usageVO.getTotalTokens(), usageVO.getContextTokens());
            usageVO.setConversationTotalTokens(newTotal);
        });
        agent.setObservationElider(observationElider);
        agent.setLoopBreaker(loopBreakerFactory.create());
        agent.setStepCondenser(stepHistoryCondenser);
        agent.setToolOutputGuard(toolOutputGuard);
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

            @Override
            public void onAborted(int step, String content, String reasoningContent) {
                conversationService.saveMessage(conversation.getId(), "assistant", content, step, "aborted",
                        reasoningContent, null);
            }
        });
        agent.runStream(request.getMessage(), taskId, channel);
    }

    private void validateDbConfigs(Long userId, List<Long> dbConfigIds) {
        for (Long configId : dbConfigIds) {
            dbConfigService.validateConfigForChat(userId, configId);
        }
    }

    /**
     * 按所选配置的数据库类型生成方言提示（按类型去重），注入 Agent 系统提示。
     *
     * <p>调用时机在 {@link #validateDbConfigs} 之后，配置归属与状态已校验，
     * 此处用 {@code getById} 只读 dbType 是安全的。</p>
     */
    private String buildDialectContext(List<Long> dbConfigIds) {
        StringBuilder sb = new StringBuilder();
        dbConfigIds.stream()
                .map(configId -> {
                    com.dbgenius.model.entity.DbConfig config = dbConfigService.getById(configId);
                    return config == null ? null : DbType.fromCode(config.getDbType());
                })
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(type -> sb.append("- ").append(type.getDisplayName())
                        .append(": ").append(type.paginationHint()).append('\n'));
        return sb.toString();
    }

    private String buildDbDocContext(Long userId, List<Long> dbConfigIds) {
        StringBuilder sb = new StringBuilder();
        for (Long configId : dbConfigIds) {
            try {
                String doc = dbConfigService.getDocContent(userId, configId);
                sb.append(doc).append("\n\n");
            } catch (Exception e) {
                sb.append("Database config #").append(configId).append(": documentation not available\n\n");
            }
        }
        return sb.toString();
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
        String configIdsStr = dbConfigIds == null ? "" : dbConfigIds.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        return conversationService.createConversation(
                userId, request.getMessage(), IntentType.SQL_QUERY.getCode(), configIdsStr);
    }
}
