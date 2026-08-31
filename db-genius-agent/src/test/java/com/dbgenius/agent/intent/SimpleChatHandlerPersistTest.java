package com.dbgenius.agent.intent;

import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link SimpleChatHandler} 的中断收尾幂等性。
 *
 * <p>正常完成、上游 error、容器 onError 三条路径都可能触发收尾，但：
 * 1. 同一轮回复只允许落一条消息；
 * 2. 用量只允许记账一次（{@code updateTokenUsage} 是 {@code total_tokens += n} 的相对更新，
 *    重复执行会永久虚增会话累计值）。
 *
 * <p>用户主动终止时落 {@code type=aborted}，该 type 不在 getRecentMessages 的上下文白名单内，
 * 因此半截回答不会污染后续 LLM 上下文，但前端历史仍可读出。
 */
class SimpleChatHandlerPersistTest {

    private SimpleChatHandler handler(ConversationService conversationService) {
        return new SimpleChatHandler(null, null, conversationService);
    }

    private Object accumulatorWith(String content, String reasoning) throws Exception {
        Class<?> type = Class.forName("com.dbgenius.agent.intent.SimpleChatHandler$StreamAccumulator");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object accumulator = ctor.newInstance();
        Method appendContent = type.getDeclaredMethod("appendContent", String.class);
        Method appendReasoning = type.getDeclaredMethod("appendReasoning", String.class);
        appendContent.setAccessible(true);
        appendReasoning.setAccessible(true);
        appendContent.invoke(accumulator, content);
        appendReasoning.invoke(accumulator, reasoning);
        return accumulator;
    }

    @Test
    void shouldPersistAssistantOnlyOnceAcrossCompetingCallbacks() throws Exception {
        ConversationService conversationService = mock(ConversationService.class);
        Object accumulator = accumulatorWith("半截回答", "半截思考");
        AtomicBoolean persisted = new AtomicBoolean(false);

        Method persist = SimpleChatHandler.class.getDeclaredMethod("persistAssistant",
                Long.class, accumulator.getClass(), String.class, AtomicBoolean.class);
        persist.setAccessible(true);
        SimpleChatHandler handler = handler(conversationService);

        // 断开路径先落库，随后 complete 回调再次尝试
        persist.invoke(handler, 7L, accumulator, "aborted", persisted);
        persist.invoke(handler, 7L, accumulator, "summary", persisted);

        verify(conversationService, times(1)).saveMessage(anyLong(), anyString(), anyString(),
                anyInt(), anyString(), any(), any());
        verify(conversationService).saveMessage(eq(7L), eq("assistant"), eq("半截回答"),
                eq(-1), eq("aborted"), eq("半截思考"), eq(null));
    }

    @Test
    void shouldAccountTokenUsageOnlyOnce() throws Exception {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.updateTokenUsage(anyLong(), anyLong(), anyInt())).thenReturn(100L);

        TokenUsageAccumulator tokenUsage = new TokenUsageAccumulator();
        tokenUsage.add(new DefaultUsage(120, 40));
        AtomicBoolean usagePersisted = new AtomicBoolean(false);

        Method persistUsage = SimpleChatHandler.class.getDeclaredMethod("persistUsage",
                Long.class, TokenUsageAccumulator.class, AtomicBoolean.class);
        persistUsage.setAccessible(true);
        SimpleChatHandler handler = handler(conversationService);

        Object first = persistUsage.invoke(handler, 7L, tokenUsage, usagePersisted);
        Object second = persistUsage.invoke(handler, 7L, tokenUsage, usagePersisted);

        assertThat(first).isNotNull();
        // 第二次直接短路：total_tokens += n 的相对更新绝不能执行两次
        assertThat(second).isNull();
        verify(conversationService, times(1)).updateTokenUsage(anyLong(), anyLong(), anyInt());
    }
}
