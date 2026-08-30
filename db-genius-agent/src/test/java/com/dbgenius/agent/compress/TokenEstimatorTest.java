package com.dbgenius.agent.compress;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link TokenEstimator} 近似估算的基本行为：空输入为 0，非空输入 > 0，
 * 且列表估算等价于逐条文本估算之和。
 */
class TokenEstimatorTest {

    @Test
    void shouldReturnZeroForNullOrBlankText() {
        assertThat(TokenEstimator.estimate((String) null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    void shouldEstimatePositiveTokenCountForNonEmptyText() {
        int tokens = TokenEstimator.estimate("Hello, this is a reasonably long sentence for token counting.");
        assertThat(tokens).isPositive();
    }

    @Test
    void shouldEstimateLongerTextWithMoreTokens() {
        int shortText = TokenEstimator.estimate("hello world");
        int longText = TokenEstimator.estimate("hello world ".repeat(50));
        assertThat(longText).isGreaterThan(shortText);
    }

    @Test
    void shouldReturnZeroForNullOrEmptyMessageList() {
        assertThat(TokenEstimator.estimate((List<org.springframework.ai.chat.messages.Message>) null)).isZero();
        assertThat(TokenEstimator.estimate(List.of())).isZero();
    }

    @Test
    void shouldSumTokensAcrossMessages() {
        UserMessage userMessage = new UserMessage("查询用户表的总行数");
        AssistantMessage assistantMessage = AssistantMessage.builder().content("用户表共有 42 行。").build();

        int combined = TokenEstimator.estimate(List.of(userMessage, assistantMessage));
        int expected = TokenEstimator.estimate(userMessage.getText()) + TokenEstimator.estimate(assistantMessage.getText());

        assertThat(combined).isEqualTo(expected);
    }

    @Test
    void shouldCountToolResponsePayloadWhichHasNoMessageText() {
        // ToolResponseMessage.getText() 恒为空，但工具结果是单轮上下文膨胀的最大来源，必须计入
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call_1", "executeSql", "{\"success\":true,\"rowCount\":100,\"data\":[1,2,3]}")))
                .build();

        assertThat(toolResponse.getText()).isEmpty();
        assertThat(TokenEstimator.estimate(List.of(toolResponse))).isPositive();
    }

    @Test
    void shouldCountAssistantToolCallArguments() {
        AssistantMessage withoutToolCall = AssistantMessage.builder().content("ok").build();
        AssistantMessage withToolCall = AssistantMessage.builder()
                .content("ok")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "executeSql",
                        "{\"dbConfigId\":1,\"sql\":\"SELECT * FROM users WHERE status = 1\"}")))
                .build();

        assertThat(TokenEstimator.estimate(List.of(withToolCall)))
                .isGreaterThan(TokenEstimator.estimate(List.of(withoutToolCall)));
    }
}
