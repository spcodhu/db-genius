package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.dbgenius.model.enums.ModelConfigStatus;
import com.dbgenius.model.enums.ModelProviderType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户保存的模型配置（API Key 等敏感信息 AES-256-GCM 加密落库）。
 */
@Data
@TableName("user_model_config")
public class UserModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 */
    private Long userId;

    /** 复用内置 provider_code；自定义时可为 "custom" */
    private String providerCode;

    /** 由 providerType + providerCode 推导，冗余字段便于查询 */
    private ModelProviderType providerType;

    /** 用户给这套配置起的名字 */
    private String displayName;

    /** 实际调用的 API 地址 */
    private String baseUrl;

    /** AES-256-GCM 加密后的 API Key */
    private String apiKeyEncrypted;

    /** 模型名称，如 "deepseek-v4-pro"、"gpt-4o" */
    private String modelName;

    /** 模型最大上下文窗口（token 数），null 表示未知 */
    private Integer contextWindow;

    /** 是否为该用户的默认配置（每个用户仅一条为 1） */
    private Boolean isDefault;

    /** 状态：启用/禁用 */
    private ModelConfigStatus status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
