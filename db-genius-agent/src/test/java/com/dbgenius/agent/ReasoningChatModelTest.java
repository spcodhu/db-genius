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
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.ResponseEntity;

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

    private void stubChatCompletion(String content, String reasoningContent) {
        ChatCompletionMessage responseMessage = new ChatCompletionMessage(content,
                ChatCompletionMessage.Role.ASSISTANT, null, null, null, null, null, null, reasoningContent);
        ChatCompletion completion = new ChatCompletion("chatcmpl-1",
                List.of(new ChatCompletion.Choice(ChatCompletionFinishReason.STOP, 0, responseMessage, null)),
                1719648000L, "deepseek-v4-pro", null, null, "chat.completion", null);
        when(openAiApi.chatCompletionEntity(any())).thenReturn(ResponseEntity.ok(completion));
    }
}
