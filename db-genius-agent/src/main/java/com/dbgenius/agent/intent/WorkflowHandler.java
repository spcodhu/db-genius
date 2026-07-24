package com.dbgenius.agent.intent;

import com.dbgenius.agent.DbWorkflowAgent;
import com.dbgenius.agent.tool.ExcelParseTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.FileUploadService;
import com.dbgenius.trial.TrialGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 复杂工作流处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowHandler implements IntentHandler {

    private final ChatClient agentChatClient;
    private final DbConfigService dbConfigService;
    private final ConversationService conversationService;
    private final FileUploadService fileUploadService;
    private final SqlExecuteTool sqlExecuteTool;
    private final ExcelParseTool excelParseTool;
    private final TerminateTool terminateTool;
    private final TrialGuard trialGuard;

    @Override
    public IntentType supportedIntent() {
        return IntentType.WORKFLOW;
    }

    @Override
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId) {
        trialGuard.denyIfTrial("试用版暂不支持工作流操作");
        List<Long> dbConfigIds = request.getDbConfigIds();
        if (dbConfigIds == null || dbConfigIds.isEmpty()) {
            throw new IllegalArgumentException("工作流需要至少选择一个数据库配置");
        }

        validateDbConfigs(userId, dbConfigIds);
        String dbDoc = buildDbDocContext(userId, dbConfigIds);

        boolean hasFiles = request.getFileIds() != null && !request.getFileIds().isEmpty();
        String enhancedMessage = request.getMessage();
        if (hasFiles) {
            List<String> filePaths = request.getFileIds().stream()
                    .map(fileUploadService::getFilePath)
                    .collect(Collectors.toList());
            enhancedMessage += "\n\n[Attached files: " + String.join(", ", filePaths) + "]";
        }

        ConversationVO conversation = getOrCreateConversation(userId, request, dbConfigIds);
        conversationService.saveMessage(conversation.getId(), "user", request.getMessage(), null, "user");

        DbWorkflowAgent agent = new DbWorkflowAgent(
                agentChatClient, sqlExecuteTool, excelParseTool, terminateTool, dbDoc, hasFiles);
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
