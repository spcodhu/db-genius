package com.dbgenius.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Locale 解析配置：基于标准 Accept-Language 请求头（前端统一注入当前语言）。
 *
 * <p>支持语言与前端 7 种语言对齐；默认 en（对齐前端 FALLBACK_LOCALE）。
 * DispatcherServlet 会把解析结果写入 LocaleContextHolder，同步请求链路
 * （Controller / 全局异常处理器）直接 {@code LocaleContextHolder.getLocale()} 即可；
 * 异步链路（chatTaskExecutor）必须在 Controller 同步段取出后显式传参。</p>
 */
@Configuration
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(
                Locale.SIMPLIFIED_CHINESE,
                Locale.TRADITIONAL_CHINESE,
                Locale.ENGLISH,
                Locale.forLanguageTag("es"),
                Locale.FRENCH,
                Locale.JAPANESE,
                Locale.forLanguageTag("ms")));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
