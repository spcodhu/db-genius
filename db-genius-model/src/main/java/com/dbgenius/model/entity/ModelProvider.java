package com.dbgenius.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.dbgenius.model.enums.ModelProviderType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型提供商预设（内置 + 管理员可扩展）。
 */
@Data
@TableName("model_provider")
public class ModelProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 唯一标识，如 "deepseek"、"openai"、"ollama" */
    private String providerCode;

    /** 前端展示名，如 "DeepSeek" */
    private String displayName;

    /** 协议类型 */
    private ModelProviderType providerType;

    /** 预设的 API 地址 */
    private String defaultBaseUrl;

    /** 预设的推荐模型名，如 "deepseek-v4-pro" */
    private String defaultModel;

    /** 是否内置（内置预设不可删除） */
    private Boolean builtin;

    /** 排序权重，越小越靠前 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
