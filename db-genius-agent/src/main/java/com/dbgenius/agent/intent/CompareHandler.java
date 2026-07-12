package com.dbgenius.agent.intent;

import com.dbgenius.agent.DbCompareAgent;
import com.dbgenius.agent.tool.DbCompareTool;
import com.dbgenius.agent.tool.SqlExecuteTool;
import com.dbgenius.agent.tool.TerminateTool;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.trial.TrialGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库对比处理器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompareHandler implements IntentHandler {

    private final ChatClient chatClient;
    private final DbConfigService dbConfigService;
    private final ConversationService conversationService;
    private final DbCompareTool dbCompareTool;
    private final SqlExecuteTool sqlExecuteTool;
    private final TerminateTool terminateTool;
    private final TrialGuard trialGuard;

    @Override
    public IntentType supportedIntent() {
        return IntentType.DB_COMPARE;
    }

    @Override
    public void handle(SseEmitter emitter, String taskId, UnifiedChatRequest request,
                       IntentClassificationResult classification, Long userId) {
        trialGuard.denyIfTrial("试用版暂不支持数据库对比");
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

        String message = request.getMessage() != null ? request.getMessage()
                : "Please compare the pre and test databases and generate deployment SQL.";
        conversationService.saveMessage(conversation.getId(), "user", message, null, "user");

        DbCompareAgent agent = new DbCompareAgent(
                chatClient, dbCompareTool, sqlExecuteTool, terminateTool, preDbDoc, testDbDoc);
        agent.setSummaryCallback(markdown ->
                conversationService.saveMessage(conversation.getId(), "assistant", markdown, -1, "summary"));
        agent.runStream(message, taskId, emitter);
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
