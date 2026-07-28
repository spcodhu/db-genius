package com.dbgenius.agent;

import com.dbgenius.agent.model.AgentState;
import com.dbgenius.model.vo.SseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    /** 历史对话消息，由调用方在 runStream 前注入，随本轮消息一起发给模型。 */
    protected List<org.springframework.ai.chat.messages.Message> historyMessages = new ArrayList<>();
    /** 当前运行的 SSE 发射器，供 step 循环内部（如 think()）推送事件。 */
    protected SseEmitter emitter;
    /** step 循环的执行器：默认 commonPool（测试直接 new agent 时维持原行为），生产由 Handler 注入 chatTaskExecutor。 */
    protected Executor executor = ForkJoinPool.commonPool();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void setSummaryCallback(Consumer<String> summaryCallback) {
        this.summaryCallback = summaryCallback;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
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

    public void runStream(String userPrompt, String taskId, SseEmitter emitter) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not in IDLE state, current: " + state);
        }

        this.taskId = taskId;
        this.emitter = emitter;
        MDC.put("taskId", taskId);

        emitter.onTimeout(() -> {
            log.warn("SSE timeout for task {}", taskId);
            state = AgentState.ERROR;
            cleanup();
        });

        emitter.onCompletion(() -> {
            if (state == AgentState.RUNNING) {
                state = AgentState.FINISHED;
            }
            cleanup();
        });

        emitter.onError(e -> {
            log.error("SSE error for task {}", taskId, e);
            state = AgentState.ERROR;
            cleanup();
        });

        // 异步执行 step 循环：SseEmitter 支持跨线程 send，不会断开连接；同步执行会阻塞 Tomcat 请求线程走完整个分钟级的 ReAct 流程。
        // 使用注入的 executor（生产为 chatTaskExecutor）：分钟级长任务若堆在 ForkJoinPool commonPool，
        // 少量并发即可占满，导致新会话连 classifying 事件都发不出去（前端看到事件成批到达）
        CompletableFuture.runAsync(() -> {
            MDC.put("taskId", taskId);
            state = AgentState.RUNNING;
            try {
                onStepStart(emitter, userPrompt);

                while (currentStep < maxSteps && state == AgentState.RUNNING) {
                    currentStep++;
                    log.info("[{}] Step {}/{}", name, currentStep, maxSteps);

                    // ReAct think-then-act，前面把工具、上下文都配置好了之后这里就可以用固定流程开始执行了
                    String result = step();

                    sendEvent(emitter, SseEvent.of(taskId, currentStep, "step", result));

                    if (state == AgentState.FINISHED) break;
                }

                if (state == AgentState.RUNNING) {
                    state = AgentState.FINISHED;
                }

                onFinish(emitter, userPrompt);

                sendEvent(emitter, SseEvent.done(taskId));
                emitter.complete();
            } catch (Exception e) {
                log.error("[{}] Execution error", name, e);
                state = AgentState.ERROR;
                try {
                    sendEvent(emitter, SseEvent.error(taskId, e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {}
            } finally {
                cleanup();
                MDC.remove("taskId");
            }
        }, executor);
    }

    protected abstract String step() throws Exception;

    protected void onStepStart(SseEmitter emitter, String userPrompt) throws Exception {
    }

    protected void onFinish(SseEmitter emitter, String userPrompt) throws Exception {
    }

    protected void cleanup() {
        MDC.remove("taskId");
    }

    protected void sendEvent(SseEmitter emitter, SseEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().data(json));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    public void setState(AgentState state) {
        this.state = state;
    }
}
