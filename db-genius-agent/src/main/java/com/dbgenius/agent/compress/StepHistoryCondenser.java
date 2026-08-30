package com.dbgenius.agent.compress;

import com.dbgenius.agent.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单次 Agent 运行内的上下文压缩 <b>Tier-3 兜底策略</b>：把最早的若干步骤整体喂给模型压成一条摘要，
 * 只保留最近若干个完整步骤原样不动。
 *
 * <p><b>为什么是兜底：</b>本策略要额外付一次全量 prompt 的 token 费用与数秒到数十秒的等待，
 * 在轮次内性价比很低。默认由零成本的 Tier-1 {@link ObservationElider}（确定性观测遮蔽）先兜住，
 * 只有遮蔽之后仍逼近上下文窗口（默认 0.8）时才升级到本策略，触发概率因此被压到极低。</p>
 *
 * <p>只作用于本轮 Agent 自己生成的步骤消息（由调用方传入的 {@code turnStartIndex} 之后），
 * {@code turnStartIndex} 之前的历史消息与当前用户输入永远不动，与跨轮压缩
 * （{@link SummaryContextCompressor}）职责边界清晰分离。
 *
 * <p>压缩严格按"步骤"分组（一条 AssistantMessage + 紧随其后的 ToolResponseMessage 视为一组），
 * 绝不拆散 tool_calls/tool_response 的配对，否则 OpenAI 兼容协议会拒绝请求。
 */
@Slf4j
@Component
public class StepHistoryCondenser {

    @Value("${db-genius.context.in-run.summarize.enabled:true}")
    private boolean enabled = true;

    @Value("${db-genius.context.in-run.summarize.threshold:0.8}")
    private double threshold = 0.8;

    @Value("${db-genius.context.in-run.summarize.keep-last-steps:4}")
    private int keepLastSteps = 4;

    private static final String PROMPT_TEMPLATE = "step-condenser";

    /**
     * 若已开启且估算 token 超过 {@code contextWindow * threshold}，压缩
     * {@code messageList[turnStartIndex..]} 中除最近 {@code keepLastSteps} 个完整步骤外的更早步骤。
     *
     * @param messageList  当前运行时的消息列表（原地修改）
     * @param turnStartIndex 本轮 Agent 生成内容的起始下标（历史消息 + 当前用户输入之后），此下标之前的内容永不触碰
     * @param systemPrompt Agent 的系统提示词（计入 token 估算）
     * @param chatModel    用于生成摘要的模型（复用 Agent 已持有的 ReasoningChatModel 即可）
     * @param contextWindow 当前模型的上下文窗口大小，未知（null 或 &lt;=0）时跳过
     * @param locale       本轮请求的语言环境，决定压缩 prompt 模板语言
     * @param listener     压缩过程回调，用于把「正在压缩」推给前端；可为 null
     * @return true 表示本次执行了压缩
     */
    public boolean condenseIfNeeded(List<Message> messageList, int turnStartIndex, String systemPrompt,
                                    ChatModel chatModel, Integer contextWindow, Locale locale,
                                    ContextCompactListener listener) {
        if (!enabled || contextWindow == null || contextWindow <= 0) {
            return false;
        }
        if (turnStartIndex < 0 || turnStartIndex > messageList.size()) {
            log.warn("[StepHistoryCondenser] invalid turnStartIndex {} for messageList size {}",
                    turnStartIndex, messageList.size());
            return false;
        }

        int estimated = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimate(messageList);
        if (estimated < contextWindow * threshold) {
            return false;
        }

        List<List<Message>> groups = groupIntoSteps(messageList, turnStartIndex);
        if (groups.size() <= keepLastSteps) {
            // 步骤数不足以在保留最近 N 步的前提下再压缩，跳过（工具输出截断安全网已在前面兜底）
            return false;
        }

        int splitIndex = groups.size() - keepLastSteps;
        List<List<Message>> toCondense = groups.subList(0, splitIndex);
        List<List<Message>> toKeep = groups.subList(splitIndex, groups.size());
        List<Message> flattenedToCondense = toCondense.stream().flatMap(List::stream).toList();

        String summaryText;
        // LLM 摘要是秒级耗时操作，先推 start 事件，避免前端出现无任何反馈的等待
        notifyStart(listener);
        try {
            summaryText = condense(chatModel, flattenedToCondense, locale);
        } catch (Exception e) {
            log.warn("[StepHistoryCondenser] in-run condensation failed, skip this round: {}", e.getMessage());
            notifyEnd(listener, estimated, estimated, 0);
            return false;
        }

        List<Message> replacement = new ArrayList<>();
        replacement.add(new AssistantMessage(summaryText));
        toKeep.forEach(replacement::addAll);

        while (messageList.size() > turnStartIndex) {
            messageList.remove(messageList.size() - 1);
        }
        messageList.addAll(replacement);

        int after = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimate(messageList);
        log.info("[StepHistoryCondenser] condensed {} step(s) (estimated tokens {} -> {}), kept last {} step(s)",
                toCondense.size(), estimated, after, toKeep.size());
        notifyEnd(listener, estimated, after, toCondense.size());
        return true;
    }

    /** 回调失败绝不影响压缩本身：SSE 推送异常只记日志。 */
    private void notifyStart(ContextCompactListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onCompactStart(ContextCompactListener.TIER_SUMMARIZE);
        } catch (Exception e) {
            log.warn("[StepHistoryCondenser] compact listener (start) failed: {}", e.getMessage());
        }
    }

    private void notifyEnd(ContextCompactListener listener, int before, int after, int affectedUnits) {
        if (listener == null) {
            return;
        }
        try {
            listener.onCompactEnd(ContextCompactListener.TIER_SUMMARIZE, before, after, affectedUnits);
        } catch (Exception e) {
            log.warn("[StepHistoryCondenser] compact listener (end) failed: {}", e.getMessage());
        }
    }

    /**
     * 按步骤分组：一条非 ToolResponseMessage（UserMessage/AssistantMessage）开启新组，
     * ToolResponseMessage 始终追加到当前已开启的组——由于 Spring AI 协议保证
     * ToolResponseMessage 必然紧随其触发该调用的 AssistantMessage，此规则永不拆散配对。
     */
    private List<List<Message>> groupIntoSteps(List<Message> messageList, int turnStartIndex) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = null;
        for (int i = turnStartIndex; i < messageList.size(); i++) {
            Message message = messageList.get(i);
            if (message instanceof ToolResponseMessage && current != null) {
                current.add(message);
            } else {
                current = new ArrayList<>();
                current.add(message);
                groups.add(current);
            }
        }
        return groups;
    }

    private String condense(ChatModel chatModel, List<Message> messages, Locale locale) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : messages) {
            transcript.append(formatForTranscript(message)).append("\n\n");
        }

        String[] sections = PromptTemplateLoader.splitSections(
                PromptTemplateLoader.load(PROMPT_TEMPLATE, locale));
        String userPrompt = sections.length > 1
                ? PromptTemplateLoader.render(sections[1], Map.of("transcript", transcript.toString()))
                : transcript.toString();
        List<Message> promptMessages = List.of(
                new SystemMessage(PromptTemplateLoader.withOutputLanguage(sections[0], locale)),
                new UserMessage(userPrompt));
        ChatResponse response = chatModel.call(new Prompt(promptMessages));
        String content = response != null && response.getResult() != null
                ? response.getResult().getOutput().getText() : null;
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Empty condense response from model");
        }
        return content;
    }

    private String formatForTranscript(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            StringBuilder sb = new StringBuilder();
            toolResponseMessage.getResponses().forEach(r ->
                    sb.append("[tool:").append(r.name()).append("] ").append(r.responseData()).append("\n"));
            return sb.toString();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return "[assistant] " + (assistantMessage.getText() != null ? assistantMessage.getText() : "");
        }
        if (message instanceof UserMessage) {
            return "[user] " + (message.getText() != null ? message.getText() : "");
        }
        return "[" + message.getMessageType() + "] " + (message.getText() != null ? message.getText() : "");
    }
}
