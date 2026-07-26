package com.dbgenius.model.enums;

import com.dbgenius.common.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 目标数据库类型枚举。
 *
 * <p><b>设计说明：</b>本枚举是多数据库支持的「类型标识」核心，与
 * {@code com.dbgenius.service.database.DatabaseAdapter}（策略模式）一一对应。
 * 新增数据库类型（包括未来的向量数据库）时，只需在此追加枚举值并实现对应的
 * {@code DatabaseAdapter} Bean，即可被 {@code DatabaseAdapterRegistry} 自动注册，
 * 无需改动任何既有代码（开闭原则）。</p>
 *
 * <p><b>存储约定：</b>{@code db_config.db_type} 列存储 {@link #code} 小写字符串
 * （如 "mysql"），保持与历史数据及前端接口的兼容，因此本枚举不挂 MyBatis-Plus 的
 * {@code @EnumValue}，实体字段仍为 String，代码内通过 {@link #fromCode(String)} 转换。</p>
 */
@Getter
@AllArgsConstructor
public enum DbType {

    /** MySQL（关系型，JDBC） */
    MYSQL("mysql", "MySQL", true),

    /** PostgreSQL（关系型，JDBC） */
    POSTGRESQL("postgresql", "PostgreSQL", true),

    /** SQLite（嵌入式关系型，JDBC，dbName 即数据库文件路径） */
    SQLITE("sqlite", "SQLite", true),

    /** MongoDB（文档型，非 JDBC，使用官方同步驱动） */
    MONGODB("mongodb", "MongoDB", false);

    /** 存入 db_config.db_type 列与接口传输的类型编码 */
    private final String code;

    /** 展示名称（用于文档标题等） */
    private final String displayName;

    /** 是否为 SQL 系数据库（决定能否使用 SQL 执行工具） */
    private final boolean sqlBased;

    /**
     * 按类型编码解析枚举。
     *
     * @param code 类型编码；为 null 或空白时按历史兼容规则回退为 {@link #MYSQL}
     * @return 对应的枚举值
     * @throws BusinessException 编码不被支持时抛出（HTTP 400）
     */
    public static DbType fromCode(String code) {
        // 历史数据与缺省请求均无 dbType 概念，统一按 mysql 兜底
        if (code == null || code.isBlank()) {
            return MYSQL;
        }
        for (DbType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new BusinessException(400, "Unsupported database type: " + code
                + ". Supported types: mysql, postgresql, sqlite, mongodb");
    }
}
