package com.dbgenius.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link ToolCallAgent} 通过 {@link ToolCallAgent.AgentMessageSink} 输出每步
 * 思考内容与工具调用/执行结果的落库载荷：
 * 1. think() 输出正文 + 归一化推理 + 工具调用 JSON；
 * 2. act() 输出工具执行结果 JSON；
 * 3. 非推理供应商（metadata 无 reasoning）时推理载荷为 null。
 */
class ToolCallAgentTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ReasoningChatModel reasoningChatModel;
    private ToolCallAgent.AgentMessageSink sink;

    /** 测试用假工具：执行后返回固定文本，不走真实 SQL。 */
    public static class EchoTool {
        @Tool(description = "echo text")
        public String echo(@ToolParam(description = "text to echo") String text) {
            return "echo:" + text;
        }
    }

    @BeforeEach
    void setUp() {
        reasoningChatModel = mock(ReasoningChatModel.class);
        sink = mock(ToolCallAgent.AgentMessageSink.class);

        // 让 onFinish 的流式总结走正常返回，避免 fallback 堆栈噪音
        when(reasoningChatModel.streamAggregated(any(), any(), any()))
                .thenReturn(responseOf(AssistantMessage.builder().content("## Summary\n\nmock summary").build()));
    }

    @Test
    void shouldEmitAssistantWithReasoningAndToolCallsThenToolResponses() throws Exception {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("我先确认表结构。")
                .properties(Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "需要先确认表结构再生成 SQL"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "echo", "{\"text\":\"hello\"}")))
                .build();
        when(reasoningChatModel.streamAggregated(any(), any()))
                .thenReturn(responseOf(assistant));

        // maxSteps=1：只跑一轮 think+act，避免重复循环干扰断言
        ToolCallAgent agent = new ToolCallAgent("TestAgent", "system", null, 1,
                reasoningChatModel, new EchoTool());
        agent.setMessageSink(sink);
        // 直执行 executor：runStream 同步跑完，无需等待异步
        agent.setExecutor(Runnable::run);
        StringBuilder summary = new StringBuilder();
        agent.setSummaryCallback(summary::append);
        agent.runStream("帮我查一下用户表");

        // 流式总结聚合全文后仍经 summaryCallback 落库
        assertThat(summary.toString()).isEqualTo("## Summary\n\nmock summary");

        // think() 载荷：step=1、正文、归一化推理、工具调用 JSON
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasoningCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toolCallsCaptor = ArgumentCaptor.forClass(String.class);
        verify(sink).onAssistant(org.mockito.ArgumentMatchers.eq(1), contentCaptor.capture(),
                reasoningCaptor.capture(), toolCallsCaptor.capture());

        assertThat(contentCaptor.getValue()).isEqualTo("我先确认表结构。");
        assertThat(reasoningCaptor.getValue()).isEqualTo("需要先确认表结构再生成 SQL");
        JsonNode toolCalls = objectMapper.readTree(toolCallsCaptor.getValue());
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).get("id").asText()).isEqualTo("call_1");
        assertThat(toolCalls.get(0).get("name").asText()).isEqualTo("echo");
        assertThat(toolCalls.get(0).get("arguments").asText()).isEqualTo("{\"text\":\"hello\"}");

        // act() 载荷：step=1、工具执行结果 JSON
        ArgumentCaptor<String> responsesCaptor = ArgumentCaptor.forClass(String.class);
        verify(sink).onToolResponses(org.mockito.ArgumentMatchers.eq(1), responsesCaptor.capture());
        JsonNode responses = objectMapper.readTree(responsesCaptor.getValue());
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).get("name").asText()).isEqualTo("echo");
        // Spring AI 工具执行结果会经 Jackson 序列化（字符串带引号），与模型实际看到的载荷一致
        assertThat(responses.get(0).get("result").asText()).isEqualTo("\"echo:hello\"");
    }

    @Test
    void shouldEmitNullReasoningWhenProviderHasNoThinkingContent() {
        // 非推理模型（如 gpt-4o / Ollama llama3.1）：metadata 无 reasoning，正文直接回答、无工具调用
        AssistantMessage assistant = AssistantMessage.builder()
                .content("用户表共有 42 行。")
                .properties(Map.of())
                .build();
        when(reasoningChatModel.streamAggregated(any(), any()))
                .thenReturn(responseOf(assistant));

        ToolCallAgent agent = new ToolCallAgent("TestAgent", "system", null, 1,
                reasoningChatModel, new EchoTool());
        agent.setMessageSink(sink);
        agent.setExecutor(Runnable::run);
        agent.runStream("用户表有多少行？");

        verify(sink).onAssistant(1, "用户表共有 42 行。", null, null);
        verify(sink, org.mockito.Mockito.never())
                .onToolResponses(org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    private ChatResponse responseOf(AssistantMessage assistant) {
        return new ChatResponse(
                List.of(new Generation(assistant, ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().build());
    }
}
