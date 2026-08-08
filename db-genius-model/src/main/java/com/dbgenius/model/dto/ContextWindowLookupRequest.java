package com.dbgenius.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 上下文窗口远程查询请求（apiKey 走 body，避免进访问日志）。
 */
@Data
public class ContextWindowLookupRequest {

    @NotBlank(message = "baseUrl is required")
    @Size(max = 256)
    private String baseUrl;

    @NotBlank(message = "apiKey is required")
    private String apiKey;

    @NotBlank(message = "modelName is required")
    @Size(max = 128)
    private String modelName;
}
