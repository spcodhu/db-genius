package com.dbgenius.agent.stream;

import com.dbgenius.model.vo.SseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 输出通道：全链路唯一的事件出口，统一承载「客户端已断开」这一状态。
 *
 * <p>解决的问题：客户端主动终止会话是<b>正常业务事件</b>，但 Servlet 容器把它表达为异常
 * （写入时 {@code AsyncRequestNotUsableException}，emitter 完成后再写则是
 * {@code IllegalStateException}）。原先各处 {@code sendEvent} 各自 try/catch，断开状态
 * 无法从 Tomcat 线程传播到 chatTaskExecutor 线程，导致：① 日志刷出多段无意义堆栈；
 * ② Agent 继续空转（继续调模型烧 token、继续执行 SQL）。
 *
 * <p>本类把断开收敛为一个 {@link #isAborted()} 布尔量：任意一次写失败或生命周期回调都会
 * 置位，置位后所有写操作静默丢弃；Agent 循环与 LLM 流式调用据此提前收敛。
 *
 * <p>线程安全：{@code aborted} 为 CAS 置位，{@link SseEmitter} 本身支持跨线程 send。
 */
@Slf4j
public class SseChannel {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SseEmitter emitter;
    private final String taskId;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public SseChannel(SseEmitter emitter, String taskId) {
        this.emitter = emitter;
        this.taskId = taskId;
    }

    public SseEmitter getEmitter() {
        return emitter;
    }

    public String getTaskId() {
        return taskId;
    }

    public boolean isAborted() {
        return aborted.get();
    }

    /**
     * 标记客户端已断开。首次置位打一条 INFO（无堆栈），重复调用完全静默。
     *
     * @param reason 断开原因，仅用于日志（如 write-failure / container-error / timeout / client-stop）
     * @return 本次调用是否为首次置位
     */
    public boolean markAborted(String reason) {
        if (aborted.compareAndSet(false, true)) {
            log.info("[chat] client disconnected, task={}, reason={}", taskId, reason);
            return true;
        }
        return false;
    }

    /**
     * 推送一个事件。已断开则直接丢弃；写入失败（客户端断开）则置位并降级为 DEBUG，
     * 绝不向调用方抛异常——SSE 推送失败不应打断业务流程的收尾逻辑。
     */
    public void send(SseEvent event) {
        if (aborted.get() || completed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            // AsyncRequestNotUsableException 继承 IOException：写失败 / 容器错误通知 / 异步请求已结束
            markAborted("write-failure");
            log.debug("[chat] SSE write failed, task={}: {}", taskId, e.getMessage());
        } catch (IllegalStateException e) {
            // ResponseBodyEmitter has already completed
            markAborted("emitter-completed");
            log.debug("[chat] SSE emitter already completed, task={}: {}", taskId, e.getMessage());
        } catch (Exception e) {
            markAborted("send-error");
            log.warn("[chat] SSE send error, task={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 尽力而为地推送一个终态事件，<b>忽略 aborted 标志</b>。
     *
     * <p>用于中断收尾时的 {@code aborted} 事件：服务端超时、或客户端只是停止读取但 socket
     * 仍可写时，前端能收到明确的终止信号；socket 已断则本次写入失败被静默吞掉。
     */
    public void sendFinal(SseEvent event) {
        if (completed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.debug("[chat] final SSE event not delivered, task={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 结束流。幂等，且吞掉 complete 自身的异常（客户端已断开时 Tomcat 可能再抛一次）。
     */
    public void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[chat] SSE complete failed, task={}: {}", taskId, e.getMessage());
        }
    }
}
