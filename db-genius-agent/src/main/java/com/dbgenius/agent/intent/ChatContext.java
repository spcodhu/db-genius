package com.dbgenius.agent.intent;

import java.util.Locale;

/**
 * 分类所需的请求上下文信息。
 *
 * <p>{@code locale} 随上下文沿异步链路显式传递（chatTaskExecutor 线程中
 * LocaleContextHolder 等 ThreadLocal 已失效），供分类 prompt 模板与下游 Handler/Agent 使用。</p>
 */
public record ChatContext(
        boolean hasDbConfig,
        boolean hasFiles,
        boolean hasCompareConfig,
        Locale locale
) {
}
