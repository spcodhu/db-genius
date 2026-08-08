package com.dbgenius.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增/编辑用户模型配置的请求体。
 */
@Data
public class UserModelConfigRequest {

    /** 复用内置 provider_code，或 "custom" 表示自定义 */
    @NotBlank(message = "providerCode is required")
    private String providerCode;

    /** provider 协议类型，通常前端从预设中带过来 */
    @NotBlank(message = "providerType is required")
    private String providerType;

    /** 用户给这套配置起的名字 */
    @NotBlank(message = "displayName is required")
    @Size(max = 128)
    private String displayName;

    /** API 地址，自定义时必填；复用预设时可选（空则沿用预设 defaultBaseUrl） */
    @Size(max = 256)
    private String baseUrl;

    /** 明文 API Key（落库时加密） */
    @NotBlank(message = "apiKey is required")
    private String apiKey;

    /** 模型名 */
    @NotBlank(message = "modelName is required")
    @Size(max = 128)
    private String modelName;

    /** 模型最大上下文窗口（token 数），可选；留空则后端按已知模型注册表兜底 */
    @jakarta.validation.constraints.Positive(message = "contextWindow must be positive")
    private Integer contextWindow;
}
