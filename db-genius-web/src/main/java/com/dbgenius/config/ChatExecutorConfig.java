package com.dbgenius.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        executor.initialize();
        return executor;
    }
}
