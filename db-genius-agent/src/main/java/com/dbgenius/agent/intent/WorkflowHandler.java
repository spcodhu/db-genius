package com.dbgenius.agent.intent;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.agent.DbWorkflowAgent;
import com.dbgenius.agent.ReasoningChatModel;
import com.dbgenius.agent.ToolCallAgent;
import com.dbgenius.agent.compress.StepHistoryCondenser;
import com.dbgenius.agent.tool.FileReadTool;
import com.dbgenius.agent.tool.ImageReadTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.agent.tool.ToolOutputReadTool;
import com.dbgenius.agent.tool.guard.ToolOutputArtifactStore;
import com.dbgenius.agent.tool.guard.ToolOutputGuard;
import com.dbgenius.agent.tool.file.FileAccessGuard;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.i18n.MessageService;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.entity.UploadedFile;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.FileUploadService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 复杂工作流处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowHandler implements IntentHandler {

    private final ChatModelFactory chatModelFactory;
    private final UserModelConfigService userModelConfigService;
    private final Executor chatTaskExecutor;
    private final DbConfigService dbConfigService;
    private final ConversationService conversationService;
    private final FileUploadService fileUploadService;
    private final SqlExecuteTool sqlExecuteTool;
    private final FileReadTool fileReadTool;
    private final ImageReadTool imageReadTool;
    private final TerminateTool terminateTool;
    private final ToolOutputReadTool toolOutputReadTool;
    private final ToolOutputGuard toolOutputGuard;
    private final StepHistoryCondenser stepHistoryCondenser;
    private final MessageService messageService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int HISTORY_SIZE = 10;

    @Override
    public IntentType supportedIntent() {
        return IntentType.WORKFLOW;
    }

    @Override
    @TrialDeny(ErrorCode.TRIAL_WORKFLOW)
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId,
                       TokenUsageAccumulator tokenUsage, Locale locale) {
        List<Long> dbConfigIds = request.getDbConfigIds();

        // 先建会话并下发 conversation 事件、落库本轮用户消息，保证即使后续前置校验失败，
        // 会话与首条消息也已持久化，客户端重试可携带会话 id 复用会话、恢复上下文
        ConversationVO conversation = getOrCreateConversation(userId, request,
                dbConfigIds == null || dbConfigIds.isEmpty() ? List.of() : dbConfigIds);
        sendEvent(emitter, SseEvent.of(taskId, 0, "conversation", conversation.getId()));

        // 先取历史再保存本轮用户消息，避免把当前消息重复塞进历史
        List<org.springframework.ai.chat.messages.Message> historyMessages =
                toHistoryMessages(conversationService.getRecentMessages(conversation.getId(), HISTORY_SIZE));
        conversationService.saveMessage(conversation.getId(), "user", request.getMessage(), null, "user");

        if (dbConfigIds == null || dbConfigIds.isEmpty()) {
            throw new BusinessException(ErrorCode.WORKFLOW_NO_DB_CONFIG);
        }

        validateDbConfigs(userId, dbConfigIds);
        String dbDoc = buildDbDocContext(userId, dbConfigIds);

        boolean hasFiles = request.getFileIds() != null && !request.getFileIds().isEmpty();
        String enhancedMessage = request.getMessage();
        // taskId 始终随 ToolContext 下传（LLM 不可见），供 readToolOutput 定位本任务的工具输出制品
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put(ToolOutputArtifactStore.CONTEXT_TASK_ID, taskId);
        if (hasFiles) {
            // 入口属主校验：任一文件不属于该用户即抛 403/404，整个请求拒绝
            List<UploadedFile> files = request.getFileIds().stream()
                    .map(fileId -> fileUploadService.getOwnedFile(fileId, userId))
                    .collect(Collectors.toList());
            // 提示词只出现 [file#N: 原名] 逻辑引用，不出现任何路径/OSS key
            String fileRefs = files.stream()
                    .map(f -> "[file#" + f.getId() + ": " + f.getOriginalName() + "]")
                    .collect(Collectors.joining(", "));
            enhancedMessage += "\n\n[Attached files: " + fileRefs + "]";
            // userId 与允许访问的文件集合经 ToolContext 在 LLM 上下文之外传递给 readFile/readImage
            toolContext.put(FileAccessGuard.CONTEXT_USER_ID, userId);
            toolContext.put(FileAccessGuard.CONTEXT_ALLOWED_FILE_IDS, Set.copyOf(request.getFileIds()));
        }

        ChatModelSession session = chatModelFactory.createSession(
                userModelConfigService.getActiveConfig(userId), tokenUsage);

        DbWorkflowAgent agent = new DbWorkflowAgent(
                session.reasoningModel(), sqlExecuteTool, fileReadTool, imageReadTool, terminateTool,
                toolOutputReadTool, dbDoc, hasFiles, Map.copyOf(toolContext), locale);
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
        });
        agent.runStream(enhancedMessage, taskId, emitter);
    }

    private void validateDbConfigs(Long userId, List<Long> dbConfigIds) {
        for (Long configId : dbConfigIds) {
            dbConfigService.validateConfigForChat(userId, configId);
        }
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

    private void sendEvent(SseEmitter emitter, SseEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.warn("[WorkflowHandler] Failed to send SSE event: {}", e.getMessage());
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
        String configIdsStr = dbConfigIds == null ? "" : dbConfigIds.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        return conversationService.createConversation(
                userId, request.getMessage(), IntentType.WORKFLOW.getCode(), configIdsStr);
    }
}
