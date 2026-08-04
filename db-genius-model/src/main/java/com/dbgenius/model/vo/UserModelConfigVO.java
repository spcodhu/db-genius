package com.dbgenius.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户模型配置视图（不包含 apiKey 明文/密文，VO 不泄露敏感字段）。
 */
@Data
public class UserModelConfigVO {

    private Long id;
    private String providerCode;
    private String providerType;
    private String displayName;
    private String baseUrl;
    private String modelName;
    private Boolean isDefault;
    private Integer status;
    private String statusDesc;
    private LocalDateTime createdAt;
}
