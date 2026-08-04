package com.dbgenius.config;

import com.dbgenius.service.ModelProviderService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Chat 模型相关配置。
 *
 * <p><b>架构变更：</b>原先此处的 {@code OpenAiChatModel}、{@code ReasoningChatModel}、
 * {@code ChatClient} 等静态单例 Bean 已全部移除，改为由 {@link com.dbgenius.agent.ChatModelFactory}
 * 在运行时按用户的模型配置动态创建实例。
 *
 * <p>当前职责：系统启动时初始化内置的 ModelProvider 预设数据。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChatClientConfig {

    private final ModelProviderService modelProviderService;

    @PostConstruct
    void initModelProviders() {
        modelProviderService.initBuiltinProviders();
    }
}
