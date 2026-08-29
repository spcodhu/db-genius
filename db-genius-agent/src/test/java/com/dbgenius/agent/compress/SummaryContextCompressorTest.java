package com.dbgenius.agent.compress;

import com.dbgenius.agent.ChatModelFactory;
import com.dbgenius.agent.ChatModelSession;
import com.dbgenius.common.i18n.MessageService;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.entity.UserModelConfig;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import com.dbgenius.service.UserModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link SummaryContextCompressor}：
 * 1. 消息数不超过保留阈值时跳过压缩；
 * 2. 超过阈值时压缩更早的消息、保留最近 N 条，正确落库摘要与标记 compressed；
 * 3. 摘要模型调用异常时静默降级为未压缩，不影响原有消息。
 */
class SummaryContextCompressorTest {

    private static final Long CONVERSATION_ID = 1L;
    private static final Long USER_ID = 100L;

    private ConversationService conversationService;
    private ChatModelFactory chatModelFactory;
    private SummaryContextCompressor compressor;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(ConversationService.class);
        UserModelConfigService userModelConfigService = mock(UserModelConfigService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        MessageService messageService = mock(MessageService.class);
        compressor = new SummaryContextCompressor(
                conversationService, userModelConfigService, chatModelFactory, messageService);

        var field = SummaryContextCompressor.class.getDeclaredField("keepLastMessages");
        field.setAccessible(true);
        field.set(compressor, 6);

        when(userModelConfigService.getActiveConfig(USER_ID)).thenReturn(new UserModelConfig());
    }

    private Message message(long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(CONVERSATION_ID);
        message.setRole(role);
        message.setContent(content);
        message.setType("user");
        return message;
    }

    private void stubModelResponse(String summaryText) {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage assistant = AssistantMessage.builder().content(summaryText).build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(assistant, ChatGenerationMetadata.builder().build())),
                ChatResponseMetadata.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        when(chatModelFactory.createSession(any(UserModelConfig.class)))
                .thenReturn(new ChatModelSession(chatModel, null, chatClient));
    }

    @Test
    void shouldSkipCompressionWhenMessageCountBelowKeepLast() {
        List<Message> messages = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            messages.add(message(i, "user", "msg " + i));
        }
        when(conversationService.getInContextMessages(CONVERSATION_ID)).thenReturn(messages);

        CompressResultVO result = compressor.compress(CONVERSATION_ID, USER_ID, null, Locale.SIMPLIFIED_CHINESE);

        assertThat(result.isCompressed()).isFalse();
        verify(conversationService, never()).saveMessage(any(), any(), any(), any(), any());
        verify(conversationService, never()).markMessagesCompressed(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCompressOldMessagesAndKeepLastSix() {
        List<Message> messages = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            messages.add(message(i, i % 2 == 0 ? "assistant" : "user", "content of message " + i));
        }
        when(conversationService.getInContextMessages(CONVERSATION_ID)).thenReturn(messages);
        when(conversationService.saveMessage(eq(CONVERSATION_ID), eq("assistant"), any(), eq(-2), eq("summary")))
                .thenReturn(999L);
        stubModelResponse("## 摘要\n\n用户在查询用户表数据。");

        CompressResultVO result = compressor.compress(CONVERSATION_ID, USER_ID, null, Locale.SIMPLIFIED_CHINESE);

        assertThat(result.isCompressed()).isTrue();
        assertThat(result.getSummaryMessageId()).isEqualTo(999L);

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(conversationService).markMessagesCompressed(idsCaptor.capture());
        // 10 条消息，保留最近 6 条，前 4 条（id=1..4）被压缩
        assertThat(idsCaptor.getValue()).containsExactly(1L, 2L, 3L, 4L);

        verify(conversationService).saveMessage(eq(CONVERSATION_ID), eq("assistant"), any(), eq(-2), eq("summary"));
    }

    @Test
    void shouldReturnNotCompressedWhenModelCallFails() {
        List<Message> messages = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            messages.add(message(i, "user", "content " + i));
        }
        when(conversationService.getInContextMessages(CONVERSATION_ID)).thenReturn(messages);
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(null);

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        when(chatModelFactory.createSession(any(UserModelConfig.class)))
                .thenReturn(new ChatModelSession(chatModel, null, chatClient));

        CompressResultVO result = compressor.compress(CONVERSATION_ID, USER_ID, null, Locale.SIMPLIFIED_CHINESE);

        assertThat(result.isCompressed()).isFalse();
        verify(conversationService, never()).markMessagesCompressed(any());
        verify(conversationService, never()).saveMessage(any(), any(), any(), any(), any());
    }
}
