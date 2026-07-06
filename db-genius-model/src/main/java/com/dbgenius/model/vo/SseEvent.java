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
}
