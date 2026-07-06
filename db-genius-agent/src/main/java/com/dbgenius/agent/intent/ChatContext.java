package com.dbgenius.agent.intent;

/**
 * 分类所需的请求上下文信息
 */
public record ChatContext(
        boolean hasDbConfig,
        boolean hasFiles,
        boolean hasCompareConfig
) {
}
