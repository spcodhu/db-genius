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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
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

    /**
     * Agent 专用的 {@link ChatClient}。
     *
     * <p>容器中共有两个 ChatClient bean（定义在 db-genius-web 的 {@code ChatClientConfig}）：
     * <ul>
     *   <li>{@code chatClient}（标注 {@code @Primary}）—— 包装普通 {@code OpenAiChatModel}，
     *       模型名、base-url、api-key 来自 {@code spring.ai.openai.*} 配置；供
     *       {@code SimpleChatHandler}、{@code IntentClassifier} 等非工具调用流程使用。</li>
     *   <li>{@code agentChatClient} —— 包装 {@link com.dbgenius.agent.ReasoningChatModel}，
     *       在工具调用轮次把 reasoning_content 回传给 DeepSeek（thinking 模式的硬性要求）；
     *       供 CompareHandler / SqlQueryHandler / WorkflowHandler 三个 Agent 处理器使用。</li>
     * </ul>
     *
     * <p>注入方式：类上的 {@code @RequiredArgsConstructor} 按 final 字段生成构造器，
     * Spring 按类型找到两个候选 bean 后，这里的本意是依靠
     * “构造器参数名 {@code agentChatClient} == bean 名”完成按名匹配
     * （spring-boot 父 POM 默认开启 {@code -parameters} 编译选项，参数名才得以保留）。
     *
     * <p><b>注意</b>：Spring 对多候选的裁决顺序是
     * {@code @Qualifier} &gt; {@code @Primary} &gt; 参数名匹配（见
     * {@code DefaultListableBeanFactory#determineAutowireCandidate}）。
     * 由于存在 {@code @Primary} 的 {@code chatClient}，仅靠参数名匹配可能被 primary 抢先，
     * 稳妥做法是显式加 {@code @Qualifier("agentChatClient")}（Lombok 生成的构造器默认
     * 不会复制字段上的 @Qualifier，需配置 {@code lombok.copyableAnnotations} 或手写构造器）。
     */
    private final ChatClient agentChatClient;
    private final DbConfigService dbConfigService;
    private final ConversationService conversationService;
    private final DbCompareTool dbCompareTool;
    private final SqlExecuteTool sqlExecuteTool;
    private final TerminateTool terminateTool;
    private final TrialGuard trialGuard;
    private final ApplicationContext applicationContext;

    /**
     * 临时验证日志：通过与容器中两个 ChatClient bean 做同一性（==）比较，
     * 打印 agentChatClient 字段实际注入的是哪一个 bean。确认后可删除。
     */
    @PostConstruct
    void logInjectedChatClient() {
        boolean isAgentBean = applicationContext.getBean("agentChatClient") == agentChatClient;
        boolean isPrimaryBean = applicationContext.getBean("chatClient") == agentChatClient;
        log.info("[CompareHandler] agentChatClient 实际注入: agentChatClient(ReasoningChatModel)? {}, primary chatClient(OpenAiChatModel)? {}",
                isAgentBean, isPrimaryBean);
    }

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
                agentChatClient, dbCompareTool, sqlExecuteTool, terminateTool, preDbDoc, testDbDoc);
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
