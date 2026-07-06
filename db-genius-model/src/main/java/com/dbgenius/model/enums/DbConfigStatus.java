package com.dbgenius.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DbConfigStatus {

    /** 正在验证连通性并生成文档 */
    VERIFYING(0, "正在验证..."),

    /** 连通性验证通过，文档已生成 */
    CONNECTED(1, "连接正常"),

    /** 连通性验证失败 */
    FAILED(2, "连接异常");

    @EnumValue
    private final int code;
    private final String desc;
}
