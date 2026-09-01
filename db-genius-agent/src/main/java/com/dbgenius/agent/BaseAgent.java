package com.dbgenius.agent;

import com.dbgenius.agent.metrics.AgentMetrics;
import com.dbgenius.agent.model.AgentState;
import com.dbgenius.agent.stream.SseChannel;
import com.dbgenius.agent.usage.TokenUsageAccumulator;
import com.dbgenius.model.vo.SseEvent;
import com.dbgenius.model.vo.TokenUsageVO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

@Slf4j
@Getter
public abstract class BaseAgent {

    protected String name;
    protected String systemPrompt;
    protected String nextStepPrompt;
    protected AgentState state;
    protected int currentStep;
    protected int maxSteps;
    protected List<org.springframework.ai.chat.messages.Message> messageList;
    protected String taskId;
    protected Consumer<String> summaryCallback;
    /** 本轮请求的 token 用量累加器，runStream 结束前下发 usage 事件；可为 null（不统计） */
    protected TokenUsageAccumulator tokenUsageAccumulator;
    /** 用量快照回调：发射 usage 事件前调用，供调用方持久化并回填会话累计值 */
    protected Consumer<TokenUsageVO> usageCallback;
    /** 历史对话消息，由调用方在 runStream 前注入，随本轮消息一起发给模型。 */
    protected List<org.springframework.ai.chat.messages.Message> historyMessages = new ArrayList<>();
    /** 当前运行的 SSE 通道，供 step 循环内部（如 think()）推送事件并感知客户端断开。 */
    protected SseChannel channel;
    /** step 循环的执行器：默认 commonPool（测试直接 new agent 时维持原行为），生产由 Handler 注入 chatTaskExecutor。 */
    protected Executor executor = ForkJoinPool.commonPool();
    /** 业务指标埋点（可选装配）：未装配时全部跳过，行为与现状一致 */
    protected AgentMetrics agentMetrics;
    /** 中断收尾是否已执行：正常分支与 catch 分支都可能触达，落库/记账只允许一次 */
    private final java.util.concurrent.atomic.AtomicBoolean abortFinalized =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public void setSummaryCallback(Consumer<String> summaryCallback) {
        this.summaryCallback = summaryCallback;
    }

    public void setTokenUsageAccumulator(TokenUsageAccumulator tokenUsageAccumulator) {
        this.tokenUsageAccumulator = tokenUsageAccumulator;
    }

    public void setUsageCallback(Consumer<TokenUsageVO> usageCallback) {
        this.usageCallback = usageCallback;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void setAgentMetrics(AgentMetrics agentMetrics) {
        this.agentMetrics = agentMetrics;
    }

    public void setHistoryMessages(List<org.springframework.ai.chat.messages.Message> historyMessages) {
        this.historyMessages = historyMessages;
    }

    protected BaseAgent(String name, int maxSteps) {
        this.name = name;
        this.maxSteps = maxSteps;
        this.state = AgentState.IDLE;
        this.currentStep = 0;
        this.messageList = new ArrayList<>();
    }

    public SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, UUID.randomUUID().toString());
    }

    public SseEmitter runStream(String userPrompt, String taskId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        runStream(userPrompt, taskId, emitter);
        return emitter;
    }

    /** 便捷重载：自行包装 SseChannel（测试与独立使用场景）。 */
    public void runStream(String userPrompt, String taskId, SseEmitter emitter) {
        runStream(userPrompt, taskId, new SseChannel(emitter, taskId));
    }

    /**
     * 生产入口：复用 Router/Handler 已创建的 {@link SseChannel}，使「客户端已断开」状态
     * 在整条链路（Tomcat 线程 ↔ chatTaskExecutor 线程）内共享。
     */
    public void runStream(String userPrompt, String taskId, SseChannel channel) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not in IDLE state, current: " + state);
        }

        this.taskId = taskId;
        this.channel = channel;
        SseEmitter emitter = channel.getEmitter();
        MDC.put("taskId", taskId);

        // 客户端断开 / 超时都是正常业务事件，不是错误：只置位 aborted（由 SseChannel 打一条
        // 无堆栈 INFO），step 循环与 LLM 流式调用据此提前收敛，绝不再打 ERROR 堆栈
        emitter.onTimeout(() -> {
            channel.markAborted("timeout");
            cleanup();
        });

        emitter.onCompletion(() -> {
            if (state == AgentState.RUNNING) {
                state = AgentState.FINISHED;
            }
            cleanup();
        });

        emitter.onError(e -> {
            if (isClientDisconnect(e)) {
                channel.markAborted("container-error");
            } else {
                log.error("SSE error for task {}", taskId, e);
                state = AgentState.ERROR;
            }
            cleanup();
        });

        // 异步执行 step 循环：SseEmitter 支持跨线程 send，不会断开连接；同步执行会阻塞 Tomcat 请求线程走完整个分钟级的 ReAct 流程。
        // 使用注入的 executor（生产为 chatTaskExecutor）：分钟级长任务若堆在 ForkJoinPool commonPool，
        // 少量并发即可占满，导致新会话连 classifying 事件都发不出去（前端看到事件成批到达）
        CompletableFuture.runAsync(() -> {
            MDC.put("taskId", taskId);
            state = AgentState.RUNNING;
            try {
                onStepStart(userPrompt);

                while (currentStep < maxSteps && state == AgentState.RUNNING && !channel.isAborted()) {
                    currentStep++;
                    log.info("[{}] Step {}/{}", name, currentStep, maxSteps);

                    // ReAct think-then-act，前面把工具、上下文都配置好了之后这里就可以用固定流程开始执行了
                    String result = step();

                    if (state == AgentState.ABORTED || channel.isAborted()) {
                        break;
                    }

                    sendEvent(SseEvent.of(taskId, currentStep, "step", result));

                    if (state == AgentState.FINISHED) break;
                }

                // 循环出口归因：此处 state==FINISHED 只可能是 act() 中 doTerminate 置位；
                // 仍 RUNNING 而后被下面翻转成 FINISHED 的，才是 maxSteps 耗尽
                boolean finishedByTool = state == AgentState.FINISHED;

                if (channel.isAborted() && state != AgentState.ABORTED) {
                    state = AgentState.ABORTED;
                }

                if (state == AgentState.ABORTED) {
                    // 用户已终止：跳过总结（省掉一次全量上下文的 LLM 调用）与 done 事件，
                    // 但仍把已消耗的 token 记账落库，并交给子类落库半截内容
                    finishAborted(userPrompt);
                    return;
                }

                if (state == AgentState.RUNNING) {
                    state = AgentState.FINISHED;
                }

                onFinish(userPrompt);

                // 总结阶段才断开：markdown 已是半截内容，同样走中断收尾
                if (state == AgentState.ABORTED || channel.isAborted()) {
                    state = AgentState.ABORTED;
                    finishAborted(userPrompt);
                    return;
                }

                sendUsageEvent(taskId);
                sendEvent(SseEvent.done(taskId));
                channel.complete();
                recordTermination(terminationReason(finishedByTool));
            } catch (Exception e) {
                if (channel.isAborted() || state == AgentState.ABORTED) {
                    // 断开后残留的写失败 / 取消传播，属正常收尾，不打堆栈
                    state = AgentState.ABORTED;
                    log.debug("[{}] Aborted run threw during teardown: {}", name, e.getMessage());
                    finishAborted(userPrompt);
                    return;
                }
                log.error("[{}] Execution error", name, e);
                state = AgentState.ERROR;
                recordTermination("error");
                sendEvent(SseEvent.error(taskId, e.getMessage()));
                channel.complete();
            } finally {
                cleanup();
                MDC.remove("taskId");
            }
        }, executor);
    }

    /**
     * 中断收尾：落库半截内容 + 记账已消耗的 token + 释放 SSE 连接。
     * 任何一步失败都不允许影响其余步骤——这是最后的收尾路径，没有下一次机会。
     */
    private void finishAborted(String userPrompt) {
        if (!abortFinalized.compareAndSet(false, true)) {
            return;
        }
        log.info("[{}] Run aborted by client at step {}", name, currentStep);
        recordTermination("aborted");
        try {
            onAbort(userPrompt);
        } catch (Exception e) {
            log.warn("[{}] onAbort failed: {}", name, e.getMessage());
        }
        persistUsage();
        // socket 若仍可写（如服务端超时），给前端一个明确的终止信号；已断则静默丢弃
        channel.sendFinal(SseEvent.aborted(taskId));
        channel.complete();
    }

    /**
     * 判定 SSE 生命周期回调收到的异常是否为「客户端断开」。
     * {@code AsyncRequestNotUsableException} 继承 IOException，Broken pipe 同理。
     */
    private boolean isClientDisconnect(Throwable e) {
        Throwable cause = e;
        // 上限防御：异常链自引用时不至于死循环
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 终止原因归因（metric tag 只用枚举值）：
     * hard_stop（LoopBreaker 硬熔断）优先于 terminate_tool（doTerminate 正常收尾）——
     * 熔断后会收敛 maxSteps 并引导模型 doTerminate，此时「熔断了」才是要暴露的信号；
     * 两者都不是则为 max_steps 耗尽（最强的任务失败先行指标，无需标准答案即可判定）。
     * aborted / error 在各自收尾路径单独记录，不经本方法。
     */
    private String terminationReason(boolean finishedByTool) {
        if (isHardStopTriggered()) {
            return "hard_stop";
        }
        return finishedByTool ? "terminate_tool" : "max_steps";
    }

    /** 硬熔断是否已触发：LoopBreaker 是 ToolCallAgent 的组件，默认 false，由子类覆写。 */
    protected boolean isHardStopTriggered() {
        return false;
    }

    /** 指标埋点绝不允许影响主流程：任何异常只记日志。 */
    private void recordTermination(String reason) {
        if (agentMetrics == null) {
            return;
        }
        try {
            agentMetrics.recordTermination(name, reason, currentStep);
        } catch (Exception e) {
            log.warn("[{}] Failed to record termination metric: {}", name, e.getMessage());
        }
    }

    protected abstract String step() throws Exception;

    /**
     * 在 done 之前下发 usage 事件：先经 usageCallback 持久化（回填会话累计值），再推送快照。
     * 覆盖分类 + 多步 think + summary 的全部 LLM 调用（均由累加器自动记账）。
     */
    private void sendUsageEvent(String taskId) {
        TokenUsageVO usageVO = persistUsage();
        if (usageVO != null) {
            sendEvent(SseEvent.of(taskId, -1, "usage", usageVO));
        }
    }

    /**
     * 持久化本轮用量（中断场景也必须执行：token 已经真实消耗，不能因用户终止而漏记账）。
     *
     * @return 用量快照；无 LLM 调用时返回 null
     */
    private TokenUsageVO persistUsage() {
        if (tokenUsageAccumulator == null || tokenUsageAccumulator.getCallCount() == 0) {
            return null;
        }
        TokenUsageVO usageVO = tokenUsageAccumulator.snapshot();
        // 顺手落 token/上下文占用直方图（不新增计算，累加器已有全量口径）
        if (agentMetrics != null) {
            try {
                agentMetrics.recordUsage(name, usageVO);
            } catch (Exception e) {
                log.warn("[{}] Failed to record usage metric: {}", name, e.getMessage());
            }
        }
        if (usageCallback != null) {
            try {
                usageCallback.accept(usageVO);
            } catch (Exception e) {
                log.warn("[{}] Failed to persist token usage", name, e);
            }
        }
        return usageVO;
    }

    protected void onStepStart(String userPrompt) throws Exception {
    }

    protected void onFinish(String userPrompt) throws Exception {
    }

    /**
     * 客户端主动终止时的收尾钩子：子类在此把已生成的半截内容落库并标记中断。
     * 此时 SSE 已不可写，不要尝试推送事件。
     */
    protected void onAbort(String userPrompt) {
    }

    protected void cleanup() {
        MDC.remove("taskId");
    }

    protected void sendEvent(SseEvent event) {
        channel.send(event);
    }

    public void setState(AgentState state) {
        this.state = state;
    }
}
