package com.dbgenius.agent;

import com.dbgenius.agent.model.AgentState;
import com.dbgenius.agent.stream.SseChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证用户主动终止会话时的收敛行为：
 * 1. LLM 流返回 CLIENT_ABORTED 的半截结果 → 立即停在 think()，绝不进入 act()；
 * 2. 半截正文与思考内容经 {@link ToolCallAgent.AgentMessageSink#onAborted} 落库，
 *    且不走正常的 onAssistant / summary 流程；
 * 3. 终态为 {@link AgentState#ABORTED}，不推送 error 事件；
 * 4. SSE 写失败（客户端断开）不外抛、不打断收尾。
 */
class ToolCallAgentAbortTest {

    private ReasoningChatModel reasoningChatModel;
    private ToolCallAgent.AgentMessageSink sink;

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
    }

    @Test
    void shouldPersistPartialContentAndSkipSummaryWhenClientAborts() {
        // 模型已吐出半截正文与思考，随后客户端断开导致流被取消
        AssistantMessage partial = AssistantMessage.builder()
                .content("正在为你统计用户表，已经确")
                .properties(Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "先看表结构再"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "echo", "{\"text\":\"hel")))
                .build();
        when(reasoningChatModel.streamAggregated(any(), any()))
                .thenReturn(abortedResponse(partial));

        ToolCallAgent agent = new ToolCallAgent("TestAgent", "system", null, 5,
                reasoningChatModel, new EchoTool());
        agent.setMessageSink(sink);
        agent.setExecutor(Runnable::run);
        StringBuilder summary = new StringBuilder();
        agent.setSummaryCallback(summary::append);

        agent.runStream("统计用户表");

        // 半截内容落库，且标记为中断（与正常 step / summary 落库分开）
        verify(sink).onAborted(1, "正在为你统计用户表，已经确", "先看表结构再");
        verify(sink, never()).onAssistant(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // 半截 tool_calls 绝不执行
        verify(sink, never()).onToolResponses(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
        // 跳过总结：省掉一次全量上下文的 LLM 调用
        assertThat(summary.toString()).isEmpty();
        assertThat(agent.getState()).isEqualTo(AgentState.ABORTED);
        // 只跑了一步就收敛，没有把 maxSteps 空转跑完
        assertThat(agent.getCurrentStep()).isEqualTo(1);
    }

    @Test
    void shouldStopLoopWhenChannelAbortedByWriteFailure() throws Exception {
        // emitter 写失败（Broken pipe）：通道置位 aborted，循环下一轮即退出
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new AsyncRequestNotUsableException("disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        SseChannel channel = new SseChannel(emitter, "task-1");

        AssistantMessage assistant = AssistantMessage.builder()
                .content("回答")
                .build();
        when(reasoningChatModel.streamAggregated(any(), any()))
                .thenReturn(normalResponse(assistant));

        ToolCallAgent agent = new ToolCallAgent("TestAgent", "system", null, 5,
                reasoningChatModel, new EchoTool());
        agent.setMessageSink(sink);
        agent.setExecutor(Runnable::run);

        agent.runStream("你好", "task-1", channel);

        assertThat(channel.isAborted()).isTrue();
        assertThat(agent.getState()).isEqualTo(AgentState.ABORTED);
        verify(sink).onAborted(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotExecuteToolsWhenClientAbortsBetweenThinkAndAct() throws Exception {
        // 模型流已正常结束（非 CLIENT_ABORTED），但 SSE 写失败暴露了断开：
        // think 与 act 之间必须刹车，绝不执行工具（workflow 下可能是写操作）
        SseEmitter emitter = mock(SseEmitter.class);
        SseChannel channel = new SseChannel(emitter, "task-1");

        AssistantMessage assistant = AssistantMessage.builder()
                .content("我来执行")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "echo", "{\"text\":\"hello\"}")))
                .build();
        when(reasoningChatModel.streamAggregated(any(), any()))
                .thenAnswer(invocation -> {
                    // 模型流结束后、act 之前客户端断开
                    channel.markAborted("write-failure");
                    return normalResponse(assistant);
                });

        ToolCallAgent agent = new ToolCallAgent("TestAgent", "system", null, 5,
                reasoningChatModel, new EchoTool());
        agent.setMessageSink(sink);
        agent.setExecutor(Runnable::run);

        agent.runStream("echo hello", "task-1", channel);

        verify(sink, never()).onToolResponses(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(agent.getState()).isEqualTo(AgentState.ABORTED);
    }

    @Test
    void shouldNotWriteEmptyAbortedRowWhenFinalSummaryAlreadyPersisted() {
        // 完整答案已经落库，只是推送 summary 事件时才发现断开：
        // 不能再补一条空的 aborted，否则历史里会多出一个假的「用户在此终止」气泡
        AssistantMessage assistant = AssistantMessage.builder().content("最终答案").build();
        when(reasoningChatModel.streamAggregated(any(), any()))
                .thenReturn(normalResponse(assistant));
        when(reasoningChatModel.streamAggregated(any(), any(), any()))
                .thenReturn(normalResponse(AssistantMessage.builder().content("## 完整总结").build()));

        SseEmitter emitter = mock(SseEmitter.class);
        SseChannel channel = new SseChannel(emitter, "task-1");

        ToolCallAgent agent = new ToolCallAgent("TestAgent", "system", null, 1,
                reasoningChatModel, new EchoTool());
        agent.setMessageSink(sink);
        agent.setExecutor(Runnable::run);
        StringBuilder summary = new StringBuilder();
        agent.setSummaryCallback(markdown -> {
            summary.append(markdown);
            // 落库成功之后才发现连接已断（模拟 summary 事件推送失败）
            channel.markAborted("write-failure");
        });

        agent.runStream("提问", "task-1", channel);

        assertThat(summary.toString()).isEqualTo("## 完整总结");
        verify(sink, never()).onAborted(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    private ChatResponse abortedResponse(AssistantMessage assistant) {
        return new ChatResponse(
                List.of(new Generation(assistant, ChatGenerationMetadata.builder()
                        .finishReason(ReasoningChatModel.FINISH_REASON_CLIENT_ABORTED)
                        .build())),
                ChatResponseMetadata.builder().build());
    }

    private ChatResponse normalResponse(AssistantMessage assistant) {
        return new ChatResponse(
                List.of(new Generation(assistant, ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().build());
    }
}
