package com.dbgenius.agent;

import com.dbgenius.agent.tool.TerminateTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link ReasoningChatModel} 的核心契约：
 * 1. 请求侧：assistant 消息 metadata 中的 reasoningContent 被回传为 reasoning_content；
 * 2. 响应侧：API 返回的 reasoning_content 被写入 AssistantMessage metadata（与官方模型同一 key）。
 */
class ReasoningChatModelTest {

    private OpenAiApi openAiApi;
    private ReasoningChatModel chatModel;

    @BeforeEach
    void setUp() {
        openAiApi = mock(OpenAiApi.class);
        OpenAiChatProperties properties = new OpenAiChatProperties();
        properties.getOptions().setModel("deepseek-v4-pro");
        chatModel = new ReasoningChatModel(openAiApi, null, properties, ToolCallingManager.builder().build());
    }

    @Test
    void shouldRoundTripReasoningContentForToolCallTurns() {
        // 模拟一轮 tool call 历史：user -> assistant(带推理内容+工具调用) -> tool
        AssistantMessage assistantWithReasoning = AssistantMessage.builder()
                .content("我先查询用户表。")
                .properties(Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "需要先确认表结构再生成 SQL"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "executeSql", "{}")))
                .build();
        ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "executeSql", "rows: 42")))
                .build();

        stubChatCompletion("final answer", "推理完毕");

        Prompt prompt = new Prompt(
                List.of(new UserMessage("查一下用户数"), assistantWithReasoning, toolMessage),
                ToolCallingChatOptions.builder()
                        .toolCallbacks(ToolCallbacks.from(new TerminateTool()))
                        .internalToolExecutionEnabled(false)
                        .build());

        ChatResponse response = chatModel.call(prompt);

        // 请求侧断言
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(openAiApi).chatCompletionEntity(captor.capture());
        ChatCompletionRequest request = captor.getValue();

        assertThat(request.model()).isEqualTo("deepseek-v4-pro");
        assertThat(request.messages()).hasSize(3);
        ChatCompletionMessage sentAssistant = request.messages().get(1);
        assertThat(sentAssistant.reasoningContent()).isEqualTo("需要先确认表结构再生成 SQL");
        assertThat(sentAssistant.toolCalls()).hasSize(1);
        assertThat(request.tools()).isNotEmpty();

        // 响应侧断言
        assertThat(response.getResult().getOutput()
                .getMetadata().get(ReasoningChatModel.REASONING_CONTENT_KEY)).isEqualTo("推理完毕");
        assertThat(response.getResult().getOutput().getText()).isEqualTo("final answer");
    }

    @Test
    void shouldSendNullReasoningContentWhenMetadataMissing() {
        stubChatCompletion("ok", null);

        chatModel.call(new Prompt(List.of(new UserMessage("你好"),
                AssistantMessage.builder().content("历史回复").build())));

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(openAiApi).chatCompletionEntity(captor.capture());
        assertThat(captor.getValue().messages().get(1).reasoningContent()).isNull();
    }

    @Test
    void shouldAggregateStreamingChunksAndEmitReasoningDeltas() {
        // 历史含 tool call 轮次：验证流式路径同样回传 reasoning_content
        AssistantMessage assistantWithReasoning = AssistantMessage.builder()
                .content("我先查询用户表。")
                .properties(Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "需要先确认表结构再生成 SQL"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "executeSql", "{}")))
                .build();
        ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "executeSql", "rows: 42")))
                .build();

        // 流式分片：reasoning 两片 + 一个 tool_call 拆两片（首片带 id/name，次片只带 arguments 增量）
        ChatCompletionChunk chunk1 = chunk(delta("先确认", null), null);
        ChatCompletionChunk chunk2 = chunk(delta("表结构",
                List.of(new ChatCompletionMessage.ToolCall(0, "call_1", "function",
                        new ChatCompletionFunction("executeSql", "{\"sql\":")))), null);
        ChatCompletionChunk chunk3 = chunk(delta(null,
                List.of(new ChatCompletionMessage.ToolCall(0, null, null,
                        new ChatCompletionFunction(null, "\"SELECT 1\"}")))),
                ChatCompletionFinishReason.TOOL_CALLS);
        when(openAiApi.chatCompletionStream(any())).thenReturn(Flux.just(chunk1, chunk2, chunk3));

        Prompt prompt = new Prompt(
                List.of(new UserMessage("查一下用户数"), assistantWithReasoning, toolMessage),
                ToolCallingChatOptions.builder()
                        .toolCallbacks(ToolCallbacks.from(new TerminateTool()))
                        .internalToolExecutionEnabled(false)
                        .build());

        List<String> reasoningDeltas = new ArrayList<>();
        ChatResponse response = chatModel.streamAggregated(prompt, reasoningDeltas::add);

        // 请求侧断言：stream=true，且 reasoning_content 回传仍然生效
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(openAiApi).chatCompletionStream(captor.capture());
        ChatCompletionRequest request = captor.getValue();
        assertThat(request.stream()).isTrue();
        assertThat(request.messages().get(1).reasoningContent()).isEqualTo("需要先确认表结构再生成 SQL");
        assertThat(request.tools()).isNotEmpty();

        // 增量回调断言
        assertThat(reasoningDeltas).containsExactly("先确认", "表结构");

        // 聚合响应断言：完整 reasoning 入 metadata，tool_call 分片正确合并
        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.getMetadata().get(ReasoningChatModel.REASONING_CONTENT_KEY)).isEqualTo("先确认表结构");
        assertThat(output.getToolCalls()).hasSize(1);
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("executeSql");
        assertThat(output.getToolCalls().get(0).arguments()).isEqualTo("{\"sql\":\"SELECT 1\"}");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void normalizeReasoningShouldReturnNullForMissingOrBlank() {
        assertThat(ReasoningChatModel.normalizeReasoningContent(null)).isNull();
        assertThat(ReasoningChatModel.normalizeReasoningContent(Map.of())).isNull();
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, ""))).isNull();
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "   "))).isNull();
    }

    @Test
    void normalizeReasoningShouldTrimAndSupportFallbackKeys() {
        // 主 key 正常内容，trim 后返回
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "  思考内容  "))).isEqualTo("思考内容");

        // 供应商/Spring AI 版本差异：备用 key 兜底（reasoning_content / thinking / reasoning）
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of("reasoning_content", "snake_case_key"))).isEqualTo("snake_case_key");
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of("thinking", "thinking_key"))).isEqualTo("thinking_key");
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of("reasoning", "reasoning_key"))).isEqualTo("reasoning_key");

        // 非 String 值也兼容（toString）
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, 123))).isEqualTo("123");

        // 主 key 存在但为空时优先主 key（返回 null），不落到备用 key
        assertThat(ReasoningChatModel.normalizeReasoningContent(
                Map.of(ReasoningChatModel.REASONING_CONTENT_KEY, "", "thinking", "fallback"))).isNull();
    }

    private ChatCompletionChunk chunk(ChatCompletionMessage delta, ChatCompletionFinishReason finishReason) {
        return new ChatCompletionChunk("chatcmpl-stream-1",
                List.of(new ChatCompletionChunk.ChunkChoice(finishReason, 0, delta, null)),
                1719648000L, "deepseek-v4-pro", null, null, "chat.completion.chunk", null);
    }

    private ChatCompletionMessage delta(String reasoningContent, List<ChatCompletionMessage.ToolCall> toolCalls) {
        return new ChatCompletionMessage(null, ChatCompletionMessage.Role.ASSISTANT,
                null, null, toolCalls, null, null, null, reasoningContent);
    }

    private void stubChatCompletion(String content, String reasoningContent) {
        ChatCompletionMessage responseMessage = new ChatCompletionMessage(content,
                ChatCompletionMessage.Role.ASSISTANT, null, null, null, null, null, null, reasoningContent);
        ChatCompletion completion = new ChatCompletion("chatcmpl-1",
                List.of(new ChatCompletion.Choice(ChatCompletionFinishReason.STOP, 0, responseMessage, null)),
                1719648000L, "deepseek-v4-pro", null, null, "chat.completion", null);
        when(openAiApi.chatCompletionEntity(any())).thenReturn(ResponseEntity.ok(completion));
    }
}
