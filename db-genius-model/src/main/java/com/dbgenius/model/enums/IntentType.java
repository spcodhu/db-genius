package com.dbgenius.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 用户意图类型
 */
public enum IntentType {

    SIMPLE_CHAT("simple_chat", "简单会话问答"),
    SQL_QUERY("sql_query", "SQL 对话查询"),
    WORKFLOW("workflow", "复杂工作流"),
    DB_COMPARE("db_compare", "数据库对比");

    private final String code;

    /**
     * 内置中文描述：仅用于日志、prompt 等内部场景。
     * 面向用户的 SSE 展示文案走消息键 {@code intent.{code}}（messages.properties），
     * 由 web 层按请求 locale 解析，不再使用本字段。
     */
    private final String description;

    IntentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    @JsonCreator
    public static IntentType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        return Arrays.stream(values())
                .filter(type -> type.code.equalsIgnoreCase(normalized)
                        || type.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }
}
