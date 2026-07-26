package com.dbgenius.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 数据库配置请求 DTO。
 *
 * <p><b>多数据库支持说明：</b>本类仅保留所有类型通用的固定校验（name、dbName 必填），
 * host/port/账密等字段的具体必填项由对应
 * {@code com.dbgenius.service.database.DatabaseAdapter#validateRequest} 按类型校验——
 * SQLite 无 host/port/账密概念，MongoDB 账密可空，因此不再在此做统一注解约束。
 * 此为向后兼容的放宽：仅减少校验，不影响现有前端传参。</p>
 */
@Data
public class DbConfigRequest {

    /** 配置名称（所有类型必填） */
    @NotBlank(message = "Config name is required")
    private String name;

    /** 数据库类型编码（mysql/postgresql/sqlite/mongodb），缺省按历史兼容规则视为 mysql */
    private String dbType;

    /** 主机地址；具体必填项由对应 DatabaseAdapter.validateRequest 按类型校验（SQLite 无需填写） */
    private String host;

    /** 端口；具体必填项由对应 DatabaseAdapter.validateRequest 按类型校验（SQLite 无需填写） */
    private Integer port;

    /** 数据库名（所有类型必填；SQLite 中为数据库文件路径） */
    @NotBlank(message = "Database name is required")
    private String dbName;

    /** 用户名；具体必填项由对应 DatabaseAdapter.validateRequest 按类型校验（MongoDB 可空） */
    private String username;

    /** 密码；具体必填项由对应 DatabaseAdapter.validateRequest 按类型校验（MongoDB 可空） */
    private String password;
}
