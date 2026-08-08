package com.dbgenius.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型上下文窗口查询结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextWindowLookupVO {

    /** 查询的模型名 */
    private String modelName;

    /** 上下文窗口大小（token），未能识别时为 null */
    private Integer contextWindow;

    /** 结果来源：registry=内置注册表命中；not_found=未识别，需手填 */
    private String source;
}
