package com.dbgenius.agent.compress;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.entity.Conversation;
import com.dbgenius.model.vo.CompressResultVO;
import com.dbgenius.service.ConversationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link DefaultContextCompressService} 的策略分派：
 * 1. 按配置的默认策略码 dispatch 到对应 Bean，并把 userId 一并透传；
 * 2. 配置的策略码未注册时回退到 noop；
 * 3. 会话不存在或不属于当前用户时拒绝（404）。
 */
class DefaultContextCompressServiceTest {

    private static void setDefaultStrategy(DefaultContextCompressService service, String strategy) throws Exception {
        Field field = DefaultContextCompressService.class.getDeclaredField("defaultStrategy");
        field.setAccessible(true);
        field.set(service, strategy);
    }

    private Conversation conversation(Long id, Long userId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        return conversation;
    }

    @Test
    void shouldDispatchToConfiguredDefaultStrategyAndPassUserId() throws Exception {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getById(1L)).thenReturn(conversation(1L, 100L));

        ContextCompressor summaryCompressor = mock(ContextCompressor.class);
        when(summaryCompressor.strategyCode()).thenReturn("summary");
        ContextCompressor noopCompressor = mock(ContextCompressor.class);
        when(noopCompressor.strategyCode()).thenReturn("noop");

        CompressResultVO expected = CompressResultVO.builder().conversationId(1L).compressed(true).build();
        when(summaryCompressor.compress(1L, 100L, null)).thenReturn(expected);

        DefaultContextCompressService service = new DefaultContextCompressService(
                conversationService, List.of(summaryCompressor, noopCompressor));
        setDefaultStrategy(service, "summary");

        CompressResultVO result = service.compress(100L, 1L, null);

        assertThat(result).isEqualTo(expected);
        verify(summaryCompressor).compress(1L, 100L, null);
        verify(noopCompressor, never()).compress(any(), any(), any());
    }

    @Test
    void shouldFallBackToNoopWhenConfiguredStrategyIsUnregistered() throws Exception {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getById(1L)).thenReturn(conversation(1L, 100L));

        ContextCompressor noopCompressor = mock(ContextCompressor.class);
        when(noopCompressor.strategyCode()).thenReturn("noop");
        when(noopCompressor.compress(1L, 100L, null))
                .thenReturn(CompressResultVO.builder().conversationId(1L).compressed(false).build());

        DefaultContextCompressService service = new DefaultContextCompressService(
                conversationService, List.of(noopCompressor));
        setDefaultStrategy(service, "does-not-exist");

        CompressResultVO result = service.compress(100L, 1L, null);

        assertThat(result.isCompressed()).isFalse();
        verify(noopCompressor).compress(1L, 100L, null);
    }

    @Test
    void shouldRejectWhenConversationNotOwnedByUser() {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.getById(1L)).thenReturn(conversation(1L, 999L));
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.strategyCode()).thenReturn("summary");

        DefaultContextCompressService service = new DefaultContextCompressService(
                conversationService, List.of(compressor));

        assertThatThrownBy(() -> service.compress(100L, 1L, null))
                .isInstanceOf(BusinessException.class);
        verify(compressor, never()).compress(any(), any(), any());
    }
}
