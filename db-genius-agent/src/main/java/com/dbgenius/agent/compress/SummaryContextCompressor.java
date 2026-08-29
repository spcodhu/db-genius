package com.dbgenius.agent.compress;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.agent.prompt.PromptTemplateLoader;
import com.dbgenius.common.i18n.MessageService;
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
import java.util.Locale;
import java.util.Map;

/**
 * LLM 摘要压缩策略：保留最近 {@code keepLastMessages} 条"可入上下文"的消息原样不动，
 * 更早的消息（含此前已生成的旧摘要）整体喂给用户当前生效模型，生成一条结构化摘要，
 * 落库为 {@code type="summary"} 消息；被摘要的旧消息批量标记 {@code type="compressed"}，
 * 后续被 {@link ConversationService#getRecentMessages}/{@link ConversationService#getInContextMessages}
 * 的过滤条件天然排除。
 *
 * <p>Token 估算基于 {@link TokenEstimator}（近似值，非计费口径），仅用于
 * {@link CompressResultVO#getBeforeTokens()}/{@link CompressResultVO#getAfterTokens()} 展示。
 *
 * <p>摘要 prompt 与结果文案均按调用方传入的 locale 本地化（prompt 模板走
 * {@link PromptTemplateLoader}，用户可见文案走 {@link MessageService}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryContextCompressor implements ContextCompressor {

    public static final String CODE = "summary";

    /** 压缩摘要消息的 step 取值：区别于 ToolCallAgent 任务总结使用的 step=-1，便于审计区分两类"summary"消息 */
    private static final int SUMMARY_STEP = -2;

    private static final String PROMPT_TEMPLATE = "summary-compressor";

    private final ConversationService conversationService;
    private final UserModelConfigService userModelConfigService;
    private final ChatModelFactory chatModelFactory;
    private final MessageService messageService;

    @Value("${db-genius.context.auto-compress.keep-last-messages:6}")
    private int keepLastMessages;

    @Override
    public String strategyCode() {
        return CODE;
    }

    @Override
    public CompressResultVO compress(Long conversationId, Long userId, CompressOptions options, Locale locale) {
        List<Message> inContext = conversationService.getInContextMessages(conversationId);
        if (inContext.size() <= keepLastMessages) {
            return noopResult(conversationId,
                    messageService.get("compress.summary.skip", locale, inContext.size(), keepLastMessages));
        }

        int splitIndex = inContext.size() - keepLastMessages;
        List<Message> toSummarize = inContext.subList(0, splitIndex);
        List<Message> toKeep = inContext.subList(splitIndex, inContext.size());
        int beforeTokens = estimateTokens(inContext);

        String summaryText;
        try {
            summaryText = summarize(userId, toSummarize, options, locale);
        } catch (Exception e) {
            log.warn("[SummaryContextCompressor] LLM summarization failed for conversation {}: {}",
                    conversationId, e.getMessage());
            return noopResult(conversationId,
                    messageService.get("compress.summary.failed", locale, e.getMessage()));
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
                .message(messageService.get("compress.summary.done", locale,
                        toSummarize.size(), Math.max(0, beforeTokens - afterTokens)))
                .build();
    }

    private String summarize(Long userId, List<Message> toSummarize, CompressOptions options, Locale locale) {
        UserModelConfig config = userModelConfigService.getActiveConfig(userId);
        ChatModelSession session = chatModelFactory.createSession(config);

        boolean zh = "zh_CN".equals(PromptTemplateLoader.resolveVariant(PROMPT_TEMPLATE, locale));
        StringBuilder transcript = new StringBuilder();
        for (Message message : toSummarize) {
            String role = "summary".equals(message.getType())
                    ? (zh ? "此前摘要" : "previous summary")
                    : describeRole(message.getRole(), zh);
            transcript.append("[").append(role).append("] ").append(message.getContent()).append("\n\n");
        }

        String[] sections = PromptTemplateLoader.splitSections(
                PromptTemplateLoader.load(PROMPT_TEMPLATE, locale));
        String userTemplate = sections.length > 1 ? sections[1] : "{transcript}";
        // 未指定目标 token 数时，剔除含 {targetTokens} 的软性要求行
        String targetTokens = "";
        if (options == null || options.targetTokens() == null) {
            userTemplate = String.join("\n", userTemplate.lines()
                    .filter(line -> !line.contains("{targetTokens}"))
                    .toList());
        } else {
            targetTokens = String.valueOf(options.targetTokens());
        }
        String userPrompt = PromptTemplateLoader.render(userTemplate, Map.of(
                "transcript", transcript.toString(),
                "targetTokens", targetTokens));

        String content = session.chatClient().prompt()
                .system(PromptTemplateLoader.withOutputLanguage(sections[0], locale))
                .user(userPrompt.strip())
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Empty summary response from model");
        }
        return content;
    }

    private String describeRole(String role, boolean zh) {
        if ("user".equals(role)) {
            return zh ? "用户" : "user";
        }
        return zh ? "助手" : "assistant";
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
