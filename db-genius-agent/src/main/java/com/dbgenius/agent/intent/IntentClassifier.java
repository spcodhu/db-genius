package com.dbgenius.agent.intent;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.agent.prompt.PromptTemplateLoader;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.enums.IntentType;
import com.dbgenius.model.vo.IntentClassificationResult;
import com.dbgenius.service.UserModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 LLM 的意图分类器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentClassifier {

    private final ChatModelFactory chatModelFactory;
    private final UserModelConfigService userModelConfigService;

    public static final double CONFIDENCE_THRESHOLD = 0.7;

    private static final String PROMPT_TEMPLATE = "intent-classifier";

    public IntentClassificationResult classify(String userMessage,
                                               List<Message> recentHistory,
                                               ChatContext context,
                                               Long userId,
                                               TokenUsageAccumulator tokenUsage) {
        Locale locale = context.locale();
        String[] sections = PromptTemplateLoader.splitSections(
                PromptTemplateLoader.load(PROMPT_TEMPLATE, locale));
        boolean zh = "zh_CN".equals(PromptTemplateLoader.resolveVariant(PROMPT_TEMPLATE, locale));
        String systemPrompt = buildSystemPrompt(sections[0], context, locale);
        String historyText = formatHistory(recentHistory, zh);

        String userPrompt = sections.length > 1
                ? PromptTemplateLoader.render(sections[1], Map.of(
                        "history", historyText, "message", userMessage))
                : historyText + "\n\n" + userMessage;

        log.debug("Classifying intent for message: {}", userMessage);

        ChatModelSession session = chatModelFactory.createSession(
                userModelConfigService.getActiveConfig(userId), tokenUsage);

        return session.chatClient().prompt()
                .system(systemPrompt)
                .user(userPrompt)
                // 意图分类是每次对话的前置内部调用，thinking 模式只会增加延迟，
                // 逐调用关闭（runtime extraBody 覆盖默认配置）
                .options(OpenAiChatOptions.builder()
                        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .build())
                .call()
                .entity(IntentClassificationResult.class);
    }

    /** system 段填充上下文占位符，并在尾部拼接显式输出语言指令 */
    private String buildSystemPrompt(String systemSection, ChatContext context, Locale locale) {
        String prompt = PromptTemplateLoader.render(systemSection, Map.of(
                "hasDbConfig", String.valueOf(context.hasDbConfig()),
                "hasFiles", String.valueOf(context.hasFiles()),
                "hasCompareConfig", String.valueOf(context.hasCompareConfig())));
        return PromptTemplateLoader.withOutputLanguage(prompt, locale);
    }

    private String formatHistory(List<Message> recentHistory, boolean zh) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return zh ? "（无历史对话）" : "(no conversation history)";
        }
        StringBuilder sb = new StringBuilder(zh ? "## 最近对话历史\n\n" : "## Recent conversation history\n\n");
        for (Message message : recentHistory) {
            String role = "user".equals(message.getRole())
                    ? (zh ? "用户" : "user")
                    : (zh ? "助手" : "assistant");
            sb.append(role).append(": ").append(message.getContent()).append("\n");
        }
        return sb.toString();
    }
}
