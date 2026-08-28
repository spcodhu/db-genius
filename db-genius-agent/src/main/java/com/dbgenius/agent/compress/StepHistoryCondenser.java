package com.dbgenius.agent.compress;

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

/**
 * 单次 Agent 运行内（{@code ToolCallAgent} 多步 ReAct 循环）的上下文压缩：当 {@code messageList}
 * 估算 token 数逼近模型上下文窗口时，把最早的步骤压缩为一条摘要，只保留最近若干个完整步骤原样不动。
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

    @Value("${db-genius.context.in-run-compress.enabled:false}")
    private boolean enabled;

    @Value("${db-genius.context.in-run-compress.threshold:0.7}")
    private double threshold;

    @Value("${db-genius.context.in-run-compress.keep-last-steps:4}")
    private int keepLastSteps;

    private static final String CONDENSE_SYSTEM_PROMPT = """
            你是一个专注于压缩 Agent 任务执行记录的助手。你会看到一个数据库智能助手在完成任务过程中
            较早的若干步骤（模型的思考/工具调用/工具返回结果），请把它们压缩为一段简洁的进展总结，
            供 Agent 在后续步骤中继续参考。

            总结须包含（没有对应内容可省略，不要虚构未出现过的信息）：
            1. 已完成的关键操作及结果（如已执行的 SQL、已确认的表结构、已处理的文件等，只保留结论性数据）
            2. 已知错误与规避方式（如果出现过失败的操作或报错，必须保留，避免后续重复犯错）
            3. 当前任务的整体进展（还差哪些步骤未完成）

            直接输出总结正文（Markdown），不要输出多余的说明文字或前后缀。
            """;

    /**
     * 若已开启且估算 token 超过 {@code contextWindow * threshold}，压缩
     * {@code messageList[turnStartIndex..]} 中除最近 {@code keepLastSteps} 个完整步骤外的更早步骤。
     *
     * @param messageList  当前运行时的消息列表（原地修改）
     * @param turnStartIndex 本轮 Agent 生成内容的起始下标（历史消息 + 当前用户输入之后），此下标之前的内容永不触碰
     * @param systemPrompt Agent 的系统提示词（计入 token 估算）
     * @param chatModel    用于生成摘要的模型（复用 Agent 已持有的 ReasoningChatModel 即可）
     * @param contextWindow 当前模型的上下文窗口大小，未知（null 或 &lt;=0）时跳过
     * @return true 表示本次执行了压缩
     */
    public boolean condenseIfNeeded(List<Message> messageList, int turnStartIndex, String systemPrompt,
                                    ChatModel chatModel, Integer contextWindow) {
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
        try {
            summaryText = condense(chatModel, flattenedToCondense);
        } catch (Exception e) {
            log.warn("[StepHistoryCondenser] in-run condensation failed, skip this round: {}", e.getMessage());
            return false;
        }

        List<Message> replacement = new ArrayList<>();
        replacement.add(new AssistantMessage(summaryText));
        toKeep.forEach(replacement::addAll);

        while (messageList.size() > turnStartIndex) {
            messageList.remove(messageList.size() - 1);
        }
        messageList.addAll(replacement);

        log.info("[StepHistoryCondenser] condensed {} step(s) (estimated {} tokens) into a summary, kept last {} step(s)",
                toCondense.size(), estimated, toKeep.size());
        return true;
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

    private String condense(ChatModel chatModel, List<Message> messages) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : messages) {
            transcript.append(formatForTranscript(message)).append("\n\n");
        }

        List<Message> promptMessages = List.of(
                new SystemMessage(CONDENSE_SYSTEM_PROMPT),
                new UserMessage("以下是需要压缩的任务执行步骤记录：\n\n" + transcript));
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
