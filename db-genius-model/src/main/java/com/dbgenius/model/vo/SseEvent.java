package com.dbgenius.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SseEvent {

    private String taskId;

    private Integer step;

    private String type;

    private Object content;

    private Long timestamp;

    public static SseEvent of(String taskId, int step, String type, Object content) {
        return SseEvent.builder()
                .taskId(taskId)
                .step(step)
                .type(type)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static SseEvent done(String taskId) {
        return of(taskId, -1, "done", null);
    }

    public static SseEvent error(String taskId, String message) {
        return of(taskId, -1, "error", message);
    }

    /**
     * 用户主动终止本轮会话。半截内容已落库（message.type = aborted），
     * 前端可据此结束渲染并标记「已终止」。
     */
    public static SseEvent aborted(String taskId) {
        return of(taskId, -1, "aborted", null);
    }
}
