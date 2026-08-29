package com.dbgenius.common.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 消息文案统一出口：封装 {@link MessageSource}，按语言环境解析 messages.properties 中的文案。
 *
 * <p><b>使用约定：</b>
 * <ul>
 *   <li>同步请求链路（Controller / 异常处理器）用 {@link #get(String)}，locale 取自
 *       {@link LocaleContextHolder}（由 LocaleResolver 在请求进入时写入）；</li>
 *   <li>异步链路（chatTaskExecutor 线程池、Agent 步骤循环）ThreadLocal 已失效，
 *       必须用 {@link #get(String, Locale, Object...)} 显式传入 locale（与 userId 同风格显式传参）。</li>
 * </ul>
 *
 * <p>消息键缺失时降级返回键名本身，绝不让文案缺失演变成运行时异常。</p>
 */
@Component
@RequiredArgsConstructor
public class MessageService {

    private final MessageSource messageSource;

    /**
     * 按当前请求的 locale 解析文案（仅限同步请求线程使用）。
     */
    public String get(String key) {
        return get(key, LocaleContextHolder.getLocale());
    }

    /**
     * 按显式 locale 解析文案，{@code args} 填充消息中的 {0}/{1} 占位符。
     *
     * <p>null args 归一化为空数组，保证始终走 MessageFormat 格式化——
     * 因此文案中的单引号一律按 MessageFormat 规范写两个（''）转义。</p>
     */
    public String get(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args != null ? args : new Object[0], key, locale);
    }
}
