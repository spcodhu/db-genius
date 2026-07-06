package com.dbgenius.model.dto;

import com.dbgenius.model.enums.IntentType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 统一对话请求
 */
@Data
public class UnifiedChatRequest {

    @NotBlank(message = "Message is required")
    private String message;

    private Long conversationId;

    private List<Long> dbConfigIds;

    private Long preDbConfigId;

    private Long testDbConfigId;

    private List<Long> fileIds;

    /**
     * 用户确认后的意图，传入时跳过 LLM 分类直接路由
     */
    private IntentType confirmedIntent;
}
