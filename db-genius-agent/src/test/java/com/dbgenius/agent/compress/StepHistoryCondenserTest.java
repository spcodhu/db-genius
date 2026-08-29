package com.dbgenius.agent.compress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link StepHistoryCondenser}：
 * 1. 未开启或未超过阈值时不做任何改动；
 * 2. 超过阈值时压缩更早的步骤、保留最近 N 个完整步骤，且严格不拆散
 *    AssistantMessage(tool_calls) 与其配套 ToolResponseMessage；
 * 3. turnStartIndex 之前的历史消息永不被触碰；
 * 4. 摘要模型调用异常时静默跳过本次压缩。
 */
class StepHistoryCondenserTest {

    private StepHistoryCondenser condenser;

    @BeforeEach
    void setUp() {
        condenser = new StepHistoryCondenser();
    }

    private void configure(boolean enabled, double threshold, int keepLastSteps) throws Exception {
        setField("enabled", enabled);
        setField("threshold", threshold);
        setField("keepLastSteps", keepLastSteps);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = StepHistoryCondenser.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(condenser, value);
    }

    /** 构造一个"步骤"：AssistantMessage(带 tool_calls) + 紧随其后的 ToolResponseMessage。 */
    private List<Message> stepWithToolCall(String callId, String toolName, String resultText) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, "{}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, resultText)))
                .build();
        List<Message> step = new ArrayList<>();
        step.add(assistant);
        step.add(toolResponse);
        return step;
    }

    private ChatModel modelReturning(String summaryText) {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage assistant = AssistantMessage.builder().content(summaryText).build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(assistant, ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        return chatModel;
    }

    @Test
    void shouldSkipWhenDisabled() throws Exception {
        configure(false, 0.5, 2);
        List<Message> messageList = new ArrayList<>(List.of(new UserMessage("task")));
        List<Message> snapshot = new ArrayList<>(messageList);

        boolean condensed = condenser.condenseIfNeeded(messageList, 1, "system", mock(ChatModel.class), 1000, Locale.SIMPLIFIED_CHINESE);

        assertThat(condensed).isFalse();
        assertThat(messageList).isEqualTo(snapshot);
    }

    @Test
    void shouldSkipWhenBelowThreshold() throws Exception {
        configure(true, 0.99, 1);
        List<Message> messageList = new ArrayList<>(List.of(new UserMessage("task")));
        messageList.addAll(stepWithToolCall("call_1", "echo", "short"));

        boolean condensed = condenser.condenseIfNeeded(messageList, 1, "system", mock(ChatModel.class), 1_000_000, Locale.SIMPLIFIED_CHINESE);

        assertThat(condensed).isFalse();
    }

    @Test
    void shouldCondenseOlderStepsAndKeepLastStepsIntact() throws Exception {
        // 阈值设为极低，保证一定触发；keepLastSteps=1 只保留最后一步
        configure(true, 0.0, 1);

        List<Message> messageList = new ArrayList<>();
        messageList.add(new UserMessage("帮我完成一个多步骤任务")); // turnStartIndex 之前，永不触碰
        int turnStartIndex = messageList.size();

        messageList.addAll(stepWithToolCall("call_1", "step1Tool", "result-1"));
        messageList.addAll(stepWithToolCall("call_2", "step2Tool", "result-2"));
        messageList.addAll(stepWithToolCall("call_3", "step3Tool", "result-3"));

        List<Message> beforeTurnSnapshot = new ArrayList<>(messageList.subList(0, turnStartIndex));

        boolean condensed = condenser.condenseIfNeeded(messageList, turnStartIndex, "system",
                modelReturning("## 进展总结\n\n已完成 step1、step2。"), 1000, Locale.SIMPLIFIED_CHINESE);

        assertThat(condensed).isTrue();
        // turnStartIndex 之前的消息完全不变
        assertThat(messageList.subList(0, turnStartIndex)).isEqualTo(beforeTurnSnapshot);

        // 之后应为：1 条摘要 AssistantMessage + 最后一步（Assistant+ToolResponse）
        List<Message> tail = messageList.subList(turnStartIndex, messageList.size());
        assertThat(tail).hasSize(3);
        assertThat(tail.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) tail.get(0)).getText()).contains("进展总结");

        // 最后一步的 AssistantMessage(tool_calls) 与其 ToolResponseMessage 仍然紧邻，未被拆散
        assertThat(tail.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(tail.get(2)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage keptToolResponse = (ToolResponseMessage) tail.get(2);
        assertThat(keptToolResponse.getResponses().get(0).name()).isEqualTo("step3Tool");
    }

    @Test
    void shouldSkipWhenNotEnoughStepsToKeepLastN() throws Exception {
        configure(true, 0.0, 5);
        List<Message> messageList = new ArrayList<>(List.of(new UserMessage("task")));
        int turnStartIndex = messageList.size();
        messageList.addAll(stepWithToolCall("call_1", "echo", "result"));
        List<Message> snapshot = new ArrayList<>(messageList);

        boolean condensed = condenser.condenseIfNeeded(messageList, turnStartIndex, "system",
                mock(ChatModel.class), 1000, Locale.SIMPLIFIED_CHINESE);

        assertThat(condensed).isFalse();
        assertThat(messageList).isEqualTo(snapshot);
    }

    @Test
    void shouldSkipSilentlyWhenModelCallFails() throws Exception {
        configure(true, 0.0, 1);
        List<Message> messageList = new ArrayList<>(List.of(new UserMessage("task")));
        int turnStartIndex = messageList.size();
        messageList.addAll(stepWithToolCall("call_1", "echo", "result-1"));
        messageList.addAll(stepWithToolCall("call_2", "echo", "result-2"));
        List<Message> snapshot = new ArrayList<>(messageList);

        ChatModel failingModel = mock(ChatModel.class);
        when(failingModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        boolean condensed = condenser.condenseIfNeeded(messageList, turnStartIndex, "system", failingModel, 1000, Locale.SIMPLIFIED_CHINESE);

        assertThat(condensed).isFalse();
        assertThat(messageList).isEqualTo(snapshot);
    }
}
