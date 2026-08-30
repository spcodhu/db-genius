package com.dbgenius.agent.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link LoopBreaker}：
 * 1. 前 N-1 次相同调用放行，第 N 次拦截并给出可行动的改变策略指引；
 * 2. 参数顺序不同但语义相同视为同一次调用（签名做 JSON 规范化）；
 * 3. 参数不同的调用互不影响计数；
 * 4. 一步内多工具时，只要有一个被拦截，全部不执行——否则 tool_call/tool_response 配对会断；
 * 5. 达到硬停阈值后 isHardStopTriggered 为 true，供调用方收敛 maxSteps 优雅收尾；
 * 6. 截断累计到阈值时只注入一次引导提示。
 */
class LoopBreakerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private LoopBreaker loopBreaker;

    @BeforeEach
    void setUp() {
        loopBreaker = new LoopBreaker(3, 5, 3);
    }

    private AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    @Test
    void shouldAllowFirstCallsAndBlockOnRepeatThreshold() throws Exception {
        List<AssistantMessage.ToolCall> calls =
                List.of(call("c1", "executeSql", "{\"dbConfigId\":1,\"sql\":\"SELECT 1\"}"));

        assertThat(loopBreaker.inspect(calls)).isEmpty();
        assertThat(loopBreaker.inspect(calls)).isEmpty();

        List<String> blocked = loopBreaker.inspect(calls);
        assertThat(blocked).hasSize(1);
        assertThat(objectMapper.readTree(blocked.get(0)).get("error").asText()).isEqualTo("REPEATED_CALL");
        assertThat(blocked.get(0)).contains("Do NOT repeat it").contains("doTerminate");
        assertThat(loopBreaker.isHardStopTriggered()).isFalse();
    }

    @Test
    void shouldTreatSemanticallyIdenticalArgumentsAsTheSameCall() {
        List<AssistantMessage.ToolCall> first =
                List.of(call("c1", "executeSql", "{\"dbConfigId\":1,\"sql\":\"SELECT 1\"}"));
        List<AssistantMessage.ToolCall> reordered =
                List.of(call("c2", "executeSql", "{\"sql\":\"SELECT 1\",\"dbConfigId\":1}"));

        assertThat(loopBreaker.inspect(first)).isEmpty();
        assertThat(loopBreaker.inspect(reordered)).isEmpty();
        assertThat(loopBreaker.inspect(first)).hasSize(1);
    }

    @Test
    void shouldCountDifferentArgumentsSeparately() {
        for (int i = 0; i < 5; i++) {
            List<AssistantMessage.ToolCall> calls =
                    List.of(call("c" + i, "executeSql", "{\"dbConfigId\":1,\"sql\":\"SELECT " + i + "\"}"));
            assertThat(loopBreaker.inspect(calls)).isEmpty();
        }
        assertThat(loopBreaker.isHardStopTriggered()).isFalse();
    }

    @Test
    void shouldBlockAllCallsOfAStepWhenAnyIsBlocked() throws Exception {
        AssistantMessage.ToolCall repeated = call("c1", "executeSql", "{\"sql\":\"SELECT 1\"}");
        loopBreaker.inspect(List.of(repeated));
        loopBreaker.inspect(List.of(repeated));

        List<String> blocked = loopBreaker.inspect(List.of(repeated, call("c2", "readFile", "{\"fileId\":7}")));

        // 一步内不能只执行一半，否则 tool_call 与 tool_response 的配对会断
        assertThat(blocked).hasSize(2);
        assertThat(objectMapper.readTree(blocked.get(0)).get("error").asText()).isEqualTo("REPEATED_CALL");
        assertThat(objectMapper.readTree(blocked.get(1)).get("error").asText()).isEqualTo("BLOCKED_WITH_BATCH");
    }

    @Test
    void shouldTriggerHardStopAfterEnoughRepeats() {
        List<AssistantMessage.ToolCall> calls = List.of(call("c1", "executeSql", "{\"sql\":\"SELECT 1\"}"));
        for (int i = 0; i < 5; i++) {
            loopBreaker.inspect(calls);
        }

        assertThat(loopBreaker.isHardStopTriggered()).isTrue();
        assertThat(loopBreaker.hardStopHint()).contains("doTerminate");
    }

    @Test
    void shouldEmitTruncationHintOnlyOnce() {
        assertThat(loopBreaker.recordTruncationAndNeedsHint()).isFalse();
        assertThat(loopBreaker.recordTruncationAndNeedsHint()).isFalse();
        assertThat(loopBreaker.recordTruncationAndNeedsHint()).isTrue();
        assertThat(loopBreaker.recordTruncationAndNeedsHint()).isFalse();
        assertThat(loopBreaker.narrowingHint()).contains("Narrow the scope");
    }

    @Test
    void shouldIgnoreEmptyToolCalls() {
        assertThat(loopBreaker.inspect(null)).isEmpty();
        assertThat(loopBreaker.inspect(List.of())).isEmpty();
    }
}
