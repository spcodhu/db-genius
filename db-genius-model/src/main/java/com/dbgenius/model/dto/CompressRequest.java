package com.dbgenius.model.dto;

import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 主动压缩上下文请求（字段均可选）。
 */
@Data
public class CompressRequest {

    /** 期望压缩到的目标 token 数，供未来压缩策略参考 */
    @Positive(message = "targetTokens must be positive")
    private Integer targetTokens;
}
