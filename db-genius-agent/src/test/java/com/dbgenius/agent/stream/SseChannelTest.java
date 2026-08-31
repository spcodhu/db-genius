package com.dbgenius.agent.stream;

import com.dbgenius.model.vo.SseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证 {@link SseChannel} 把「客户端断开」收敛为一个状态位的契约：
 * 1. 容器抛出的两种断开表达（{@code AsyncRequestNotUsableException} / {@code IllegalStateException}）
 *    都被吞掉并置位 aborted，绝不向业务代码外抛；
 * 2. 置位后所有写操作静默丢弃，不再触碰 emitter；
 * 3. complete 幂等且吞掉自身异常。
 */
class SseChannelTest {

    private SseEmitter emitter;
    private SseChannel channel;

    @BeforeEach
    void setUp() {
        emitter = mock(SseEmitter.class);
        channel = new SseChannel(emitter, "task-1");
    }

    @Test
    void shouldMarkAbortedAndSwallowWhenClientDisconnectsDuringWrite() throws Exception {
        doThrow(new AsyncRequestNotUsableException("Servlet container error notification for disconnected client"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> channel.send(SseEvent.of("task-1", 1, "step", "hello")))
                .doesNotThrowAnyException();

        assertThat(channel.isAborted()).isTrue();
    }

    @Test
    void shouldMarkAbortedWhenEmitterAlreadyCompleted() throws Exception {
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> channel.send(SseEvent.of("task-1", 1, "step", "hello")))
                .doesNotThrowAnyException();

        assertThat(channel.isAborted()).isTrue();
    }

    @Test
    void shouldDiscardSubsequentEventsOnceAborted() throws Exception {
        doThrow(new AsyncRequestNotUsableException("disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        channel.send(SseEvent.of("task-1", 1, "reasoning", "a"));
        channel.send(SseEvent.of("task-1", 1, "reasoning", "b"));
        channel.send(SseEvent.done("task-1"));

        // 只有第一次真正触达 emitter，其余在通道层直接丢弃（零成本）
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void markAbortedShouldBeIdempotent() {
        assertThat(channel.markAborted("write-failure")).isTrue();
        assertThat(channel.markAborted("container-error")).isFalse();
        assertThat(channel.isAborted()).isTrue();
    }

    @Test
    void completeShouldBeIdempotentAndSwallowErrors() {
        doThrow(new IllegalStateException("already completed")).when(emitter).complete();

        assertThatCode(() -> {
            channel.complete();
            channel.complete();
        }).doesNotThrowAnyException();

        verify(emitter, times(1)).complete();
    }

    @Test
    void shouldNotWriteAfterComplete() throws Exception {
        channel.complete();
        channel.send(SseEvent.done("task-1"));

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
