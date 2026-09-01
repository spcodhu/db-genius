package com.dbgenius.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final TaskIdInterceptor taskIdInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(taskIdInterceptor)
                .addPathPatterns("/**");

        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/trial/status",
                        "/sales/contact",
                        "/health",
                        // actuator 端点（/api/actuator/**）供 Prometheus 抓取与探活，不做登录拦截；
                        // 生产环境如需保护应在网关/网络层收口，而不是在这里加鉴权
                        "/actuator/**"
                );
    }
}
