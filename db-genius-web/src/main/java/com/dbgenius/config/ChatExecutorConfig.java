package com.dbgenius.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 聊天/Agent 任务专用线程池。
 *
 * <p>SSE 会话的意图路由和 Agent step 循环都是分钟级长任务，且大部分时间在
 * 同步等待 LLM 响应。若裸用 ForkJoinPool commonPool（{@code CompletableFuture.runAsync}
 * 不传 executor 的默认行为），少量并发会话即可把 commonPool（大小 = CPU 核数-1）
 * 占满，后续请求的路由任务排队，连 classifying 事件都发不出去，前端表现为
 * 所有事件成批同时到达。独立线程池做资源隔离，bean 名即注入时的按名匹配依据。
 *
 * <p>{@link ContextPropagatingTaskDecorator}：trace/observation 上下文与 MDC 一样是
 * ThreadLocal，不会自动跨线程。装饰器在任务提交时快照当前线程的 Observation
 * （经 micrometer context-propagation 服务注册的 ObservationThreadLocalAccessor）
 * 并在工作线程恢复，否则 IntentRouter 之后所有 span 都会变成孤儿 trace。
 * MDC 的 taskId 仍由各处异步块手动补齐（装饰器对本项目 MDC 非必需）。
 */
@Configuration
public class ChatExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public Executor chatTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-task-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // 一行搞定 trace 上下文跨线程传播（来自 spring-core 6.2.5，已在 classpath 上）
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
