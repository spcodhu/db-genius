package com.dbgenius.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class TaskIdInterceptor implements HandlerInterceptor {

    private static final String TASK_ID_KEY = "taskId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String taskId = request.getHeader("X-Task-Id");
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }
        MDC.put(TASK_ID_KEY, taskId);
        response.setHeader("X-Task-Id", taskId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(TASK_ID_KEY);
    }
}
