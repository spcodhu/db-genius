package com.dbgenius.agent.intent;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.agent.DbWorkflowAgent;
import com.dbgenius.agent.ReasoningChatModel;
import com.dbgenius.agent.tool.FileReadTool;
import com.dbgenius.agent.tool.ImageReadTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.agent.tool.file.FileAccessGuard;
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
import java.util.List;
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int HISTORY_SIZE = 10;

    @Override
    public IntentType supportedIntent() {
        return IntentType.WORKFLOW;
    }

    @Override
    @TrialDeny("试用版暂不支持工作流操作")
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId) {
        List<Long> dbConfigIds = request.getDbConfigIds();
        if (dbConfigIds == null || dbConfigIds.isEmpty()) {
            throw new IllegalArgumentException("工作流需要至少选择一个数据库配置");
        }

        validateDbConfigs(userId, dbConfigIds);
        String dbDoc = buildDbDocContext(userId, dbConfigIds);

        boolean hasFiles = request.getFileIds() != null && !request.getFileIds().isEmpty();
        String enhancedMessage = request.getMessage();
        Map<String, Object> toolContext = Map.of();
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
            toolContext = Map.of(
                    FileAccessGuard.CONTEXT_USER_ID, userId,
                    FileAccessGuard.CONTEXT_ALLOWED_FILE_IDS, Set.copyOf(request.getFileIds()));
        }

        ConversationVO conversation = getOrCreateConversation(userId, request, dbConfigIds);
        sendEvent(emitter, SseEvent.of(taskId, 0, "conversation", conversation.getId()));

        // 先取历史再保存本轮用户消息，避免把当前消息重复塞进历史
        List<org.springframework.ai.chat.messages.Message> historyMessages =
                toHistoryMessages(conversationService.getRecentMessages(conversation.getId(), HISTORY_SIZE));
        conversationService.saveMessage(conversation.getId(), "user", request.getMessage(), null, "user");

        ChatModelSession session = chatModelFactory.createSession(
                userModelConfigService.getActiveConfig(userId));

        DbWorkflowAgent agent = new DbWorkflowAgent(
                session.agentChatClient(), session.reasoningModel(), sqlExecuteTool, fileReadTool, imageReadTool, terminateTool,
                dbDoc, hasFiles, toolContext);
        agent.setHistoryMessages(historyMessages);
        agent.setExecutor(chatTaskExecutor);
        agent.setSummaryCallback(markdown ->
                conversationService.saveMessage(conversation.getId(), "assistant", markdown, -1, "summary"));
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
        String configIdsStr = dbConfigIds.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        return conversationService.createConversation(
                userId, request.getMessage(), IntentType.WORKFLOW.getCode(), configIdsStr);
    }
}
