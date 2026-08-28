package com.dbgenius.agent.compress;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.entity.UserModelConfig;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.UserModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM 摘要压缩策略：保留最近 {@code keepLastMessages} 条"可入上下文"的消息原样不动，
 * 更早的消息（含此前已生成的旧摘要）整体喂给用户当前生效模型，生成一条结构化摘要，
 * 落库为 {@code type="summary"} 消息；被摘要的旧消息批量标记 {@code type="compressed"}，
 * 后续被 {@link ConversationService#getRecentMessages}/{@link ConversationService#getInContextMessages}
 * 的过滤条件天然排除。
 *
 * <p>Token 估算基于 {@link TokenEstimator}（近似值，非计费口径），仅用于
 * {@link CompressResultVO#getBeforeTokens()}/{@link CompressResultVO#getAfterTokens()} 展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryContextCompressor implements ContextCompressor {

    public static final String CODE = "summary";

    /** 压缩摘要消息的 step 取值：区别于 ToolCallAgent 任务总结使用的 step=-1，便于审计区分两类"summary"消息 */
    private static final int SUMMARY_STEP = -2;

    private final ConversationService conversationService;
    private final UserModelConfigService userModelConfigService;
    private final ChatModelFactory chatModelFactory;

    @Value("${db-genius.context.auto-compress.keep-last-messages:6}")
    private int keepLastMessages;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个专注于压缩对话历史的助手。你会看到一段数据库智能助手与用户的历史对话
            （可能已包含此前生成的摘要），请把它压缩为一段简洁但信息完整的结构化摘要，供后续对话继续使用。

            摘要必须包含以下部分（没有对应内容的部分可省略，但绝不能虚构未出现过的信息）：
            1. 用户目标/任务描述
            2. 关键结论与已确认事实（涉及的数据库/表名、已执行过的操作及结果摘要——只保留结论性数据，
               如行数/关键数值，不要罗列整张结果表）
            3. 已知错误与规避方式（如果对话中出现过失败的操作或报错，必须保留，不要抹除，这对避免重复犯错很重要）
            4. 尚待处理事项/用户尚未回答的问题

            不确定的信息标注"未知"，不要编造。直接输出摘要正文（Markdown），不要输出多余的说明文字或前后缀。
            """;

    @Override
    public String strategyCode() {
        return CODE;
    }

    @Override
    public CompressResultVO compress(Long conversationId, Long userId, CompressOptions options) {
        List<Message> inContext = conversationService.getInContextMessages(conversationId);
        if (inContext.size() <= keepLastMessages) {
            return noopResult(conversationId,
                    "消息数量（" + inContext.size() + "）未超过保留阈值（" + keepLastMessages + "），无需压缩");
        }

        int splitIndex = inContext.size() - keepLastMessages;
        List<Message> toSummarize = inContext.subList(0, splitIndex);
        List<Message> toKeep = inContext.subList(splitIndex, inContext.size());
        int beforeTokens = estimateTokens(inContext);

        String summaryText;
        try {
            summaryText = summarize(userId, toSummarize, options);
        } catch (Exception e) {
            log.warn("[SummaryContextCompressor] LLM summarization failed for conversation {}: {}",
                    conversationId, e.getMessage());
            return noopResult(conversationId, "压缩失败（模型摘要调用异常，已跳过本次压缩）：" + e.getMessage());
        }

        Long summaryMessageId = conversationService.saveMessage(
                conversationId, "assistant", summaryText, SUMMARY_STEP, "summary");
        conversationService.markMessagesCompressed(toSummarize.stream().map(Message::getId).toList());

        int afterTokens = TokenEstimator.estimate(summaryText) + estimateTokens(toKeep);
        log.info("[SummaryContextCompressor] conversation {} compressed {} messages, tokens {} -> {} (estimated)",
                conversationId, toSummarize.size(), beforeTokens, afterTokens);

        return CompressResultVO.builder()
                .conversationId(conversationId)
                .compressed(true)
                .beforeTokens(beforeTokens)
                .afterTokens(afterTokens)
                .summaryMessageId(summaryMessageId)
                .message("已将 " + toSummarize.size() + " 条历史消息压缩为摘要，预计减少约 "
                        + Math.max(0, beforeTokens - afterTokens) + " tokens（估算值，非计费口径）")
                .build();
    }

    private String summarize(Long userId, List<Message> toSummarize, CompressOptions options) {
        UserModelConfig config = userModelConfigService.getActiveConfig(userId);
        ChatModelSession session = chatModelFactory.createSession(config);

        StringBuilder transcript = new StringBuilder();
        for (Message message : toSummarize) {
            String role = "summary".equals(message.getType()) ? "此前摘要" : describeRole(message.getRole());
            transcript.append("[").append(role).append("] ").append(message.getContent()).append("\n\n");
        }

        StringBuilder userPrompt = new StringBuilder("以下是需要压缩的历史对话内容：\n\n").append(transcript);
        if (options != null && options.targetTokens() != null) {
            userPrompt.append("\n请尽量将摘要控制在约 ").append(options.targetTokens())
                    .append(" token 以内（软性要求，无法保证时以信息完整为先）。");
        }

        String content = session.chatClient().prompt()
                .system(SUMMARY_SYSTEM_PROMPT)
                .user(userPrompt.toString())
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Empty summary response from model");
        }
        return content;
    }

    private String describeRole(String role) {
        return "user".equals(role) ? "用户" : "助手";
    }

    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += TokenEstimator.estimate(message.getContent());
        }
        return total;
    }

    private CompressResultVO noopResult(Long conversationId, String message) {
        Conversation conversation = conversationService.getById(conversationId);
        Integer tokens = conversation != null ? conversation.getContextTokens() : null;
        return CompressResultVO.builder()
                .conversationId(conversationId)
                .compressed(false)
                .beforeTokens(tokens)
                .afterTokens(tokens)
                .summaryMessageId(null)
                .message(message)
                .build();
    }
}
