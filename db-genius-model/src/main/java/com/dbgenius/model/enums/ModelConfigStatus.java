package com.dbgenius.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户模型配置状态。
 */
@Getter
@AllArgsConstructor
public enum ModelConfigStatus {

    ENABLED(1, "已启用"),
    DISABLED(0, "已禁用");

    @EnumValue
    private final int code;
    private final String desc;
}
