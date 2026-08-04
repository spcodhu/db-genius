package com.dbgenius.model.vo;

import lombok.Data;

/**
 * 模型提供商预设（前端下拉列表使用）。
 */
@Data
public class ModelProviderVO {

    private String providerCode;
    private String displayName;
    private String providerType;
    private String defaultBaseUrl;
    private String defaultModel;
    private Boolean builtin;
    private Integer sortOrder;
}
