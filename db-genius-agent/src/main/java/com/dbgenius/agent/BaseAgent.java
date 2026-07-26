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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void setSummaryCallback(Consumer<String> summaryCallback) {
        this.summaryCallback = summaryCallback;
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

        CompletableFuture.runAsync(() -> {
            MDC.put("taskId", taskId);
            state = AgentState.RUNNING;
            try {
                onStepStart(emitter, userPrompt);

                while (currentStep < maxSteps && state == AgentState.RUNNING) {
                    currentStep++;
                    log.info("[{}] Step {}/{}", name, currentStep, maxSteps);

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
        });
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
