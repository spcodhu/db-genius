package com.dbgenius.agent.compress;

import com.dbgenius.agent.tool.guard.ToolOutputGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ObservationElider}（Tier-1 确定性观测遮蔽）：
 * 1. 未开启 / 未超阈值 / 上下文窗口未知时不做任何改动；
 * 2. 超阈值时只遮蔽较早步骤的工具结果，保留最近 N 条原样；
 * 3. 只改写 responseData 文本，<b>不增删消息</b>，tool_calls 与 tool_response 的配对与 id 恒定；
 * 4. turnStartIndex 之前的历史消息永不触碰；
 * 5. 幂等：已遮蔽的条目不会被二次遮蔽；
 * 6. 占位符带可取回的 artifactId，模型不会因数据消失而陷入重试。
 */
class ObservationEliderTest {

    private ToolOutputGuard guard;
    private ObservationElider elider;

    @BeforeEach
    void setUp() throws Exception {
        guard = ToolOutputGuard.withDefaults();
        elider = new ObservationElider(guard);
        configure(true, 0.0, 1);
    }

    private void configure(boolean enabled, double threshold, int keepLastSteps) throws Exception {
        setField("enabled", enabled);
        setField("threshold", threshold);
        setField("keepLastSteps", keepLastSteps);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ObservationElider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(elider, value);
    }

    /** 构造一个步骤：AssistantMessage(带 tool_calls) + 紧随其后的 ToolResponseMessage。 */
    private List<Message> stepWithToolCall(String callId, String toolName, String resultText) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("已执行 " + toolName)
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, "{}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, resultText)))
                .build();
        return new ArrayList<>(List.of(assistant, toolResponse));
    }

    private String bigResult(String tag) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"rowCount\":20,\"data\":[");
        for (int i = 0; i < 20; i++) {
            sb.append(i > 0 ? "," : "")
                    .append("{\"id\":").append(i)
                    .append(",\"name\":\"").append(tag).append("-user-").append(i)
                    .append("\",\"email\":\"").append(tag).append(i).append("@example.com\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void shouldSkipWhenDisabled() throws Exception {
        configure(false, 0.0, 1);
        List<Message> messageList = new ArrayList<>(List.of(new UserMessage("task")));
        messageList.addAll(stepWithToolCall("call_1", "echo", bigResult("r1")));
        messageList.addAll(stepWithToolCall("call_2", "echo", bigResult("r2")));
        List<Message> snapshot = new ArrayList<>(messageList);

        assertThat(elider.elideIfNeeded(messageList, 1, "system", 1000, "task-1").elided()).isFalse();
        assertThat(messageList).isEqualTo(snapshot);
    }

    @Test
    void shouldSkipWhenBelowThresholdOrContextWindowUnknown() throws Exception {
        configure(true, 0.99, 1);
        List<Message> messageList = new ArrayList<>(List.of(new UserMessage("task")));
        messageList.addAll(stepWithToolCall("call_1", "echo", bigResult("r1")));
        messageList.addAll(stepWithToolCall("call_2", "echo", bigResult("r2")));

        assertThat(elider.elideIfNeeded(messageList, 1, "system", 1_000_000, "task-1").elided()).isFalse();

        configure(true, 0.0, 1);
        assertThat(elider.elideIfNeeded(messageList, 1, "system", null, "task-1").elided()).isFalse();
        assertThat(elider.elideIfNeeded(messageList, 1, "system", 0, "task-1").elided()).isFalse();
    }

    @Test
    void shouldElideOlderObservationsAndKeepPairingIntact() {
        List<Message> messageList = new ArrayList<>();
        messageList.add(new UserMessage("多步骤任务")); // turnStartIndex 之前，永不触碰
        int turnStartIndex = messageList.size();
        messageList.addAll(stepWithToolCall("call_1", "step1Tool", bigResult("r1")));
        messageList.addAll(stepWithToolCall("call_2", "step2Tool", bigResult("r2")));
        messageList.addAll(stepWithToolCall("call_3", "step3Tool", bigResult("r3")));
        int sizeBefore = messageList.size();
        Message historyBefore = messageList.get(0);

        ObservationElider.Result result =
                elider.elideIfNeeded(messageList, turnStartIndex, "system", 1000, "task-1");

        assertThat(result.elided()).isTrue();
        assertThat(result.elidedCount()).isEqualTo(2);
        assertThat(result.afterTokens()).isLessThan(result.beforeTokens());
        // 不增删任何消息，历史消息原样
        assertThat(messageList).hasSize(sizeBefore);
        assertThat(messageList.get(0)).isSameAs(historyBefore);

        // 前两步的工具结果被遮蔽，最后一步原样保留
        assertThat(responseData(messageList, 2)).startsWith(ObservationElider.ELIDED_PREFIX);
        assertThat(responseData(messageList, 4)).startsWith(ObservationElider.ELIDED_PREFIX);
        assertThat(responseData(messageList, 6)).isEqualTo(bigResult("r3"));

        // tool_calls 与 tool_response 的配对与 id 对应关系保持不变
        for (int i = turnStartIndex; i < messageList.size(); i += 2) {
            AssistantMessage assistant = (AssistantMessage) messageList.get(i);
            ToolResponseMessage toolResponse = (ToolResponseMessage) messageList.get(i + 1);
            assertThat(toolResponse.getResponses().get(0).id())
                    .isEqualTo(assistant.getToolCalls().get(0).id());
        }
    }

    @Test
    void shouldOfferRetrievableArtifactIdInPlaceholder() {
        List<Message> messageList = new ArrayList<>();
        messageList.addAll(stepWithToolCall("call_1", "executeSql", bigResult("r1")));
        messageList.addAll(stepWithToolCall("call_2", "executeSql", bigResult("r2")));

        elider.elideIfNeeded(messageList, 0, "system", 1000, "task-1");

        String placeholder = responseData(messageList, 1);
        assertThat(placeholder).contains("readToolOutput").contains("artifactId=");
        String artifactId = placeholder.replaceAll("(?s).*artifactId=\"([^\"]+)\".*", "$1");
        assertThat(guard.park("task-1", "executeSql", "ignored")).isNotEqualTo(artifactId);
    }

    @Test
    void shouldBeIdempotent() {
        List<Message> messageList = new ArrayList<>();
        messageList.addAll(stepWithToolCall("call_1", "echo", bigResult("r1")));
        messageList.addAll(stepWithToolCall("call_2", "echo", bigResult("r2")));

        elider.elideIfNeeded(messageList, 0, "system", 1000, "task-1");
        String firstPass = responseData(messageList, 1);

        ObservationElider.Result second =
                elider.elideIfNeeded(messageList, 0, "system", 1000, "task-1");

        assertThat(second.elided()).isFalse();
        assertThat(responseData(messageList, 1)).isEqualTo(firstPass);
    }

    @Test
    void shouldKeepShortObservationsUntouched() {
        List<Message> messageList = new ArrayList<>();
        messageList.addAll(stepWithToolCall("call_1", "echo", "42"));
        messageList.addAll(stepWithToolCall("call_2", "echo", bigResult("r2")));

        // 最早一步的结果很短，遮蔽收益为负，直接跳过
        assertThat(elider.elideIfNeeded(messageList, 0, "system", 1000, "task-1").elided()).isFalse();
        assertThat(responseData(messageList, 1)).isEqualTo("42");
    }

    private String responseData(List<Message> messageList, int index) {
        return ((ToolResponseMessage) messageList.get(index)).getResponses().get(0).responseData();
    }
}
