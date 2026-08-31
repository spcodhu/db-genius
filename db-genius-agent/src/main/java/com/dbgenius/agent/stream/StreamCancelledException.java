package com.dbgenius.agent.stream;

/**
 * 流式 LLM 调用被主动取消的内部信号（客户端断开时由 cancelSignal 触发）。
 *
 * <p>只用于把「取消」从 Reactor 的 {@code doOnNext} 里抛出，从而向上游传播 cancel、
 * 立即释放供应商 HTTP 连接，不再空转烧 token。捕获方必须把它当作正常控制流处理，
 * 不允许当成错误记录堆栈。
 *
 * <p>不填充堆栈（{@code writableStackTrace=false}）：纯控制信号，构造开销归零。
 */
public class StreamCancelledException extends RuntimeException {

    public StreamCancelledException() {
        super("LLM stream cancelled by client disconnect", null, false, false);
    }
}
