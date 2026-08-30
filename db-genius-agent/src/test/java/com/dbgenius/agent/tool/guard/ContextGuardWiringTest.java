package com.dbgenius.agent.tool.guard;

import com.dbgenius.agent.compress.ObservationElider;
import com.dbgenius.agent.guard.LoopBreaker;
import com.dbgenius.agent.guard.LoopBreakerFactory;
import com.dbgenius.agent.tool.ToolOutputReadTool;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文治理相关 Bean 的装配冒烟测试：验证构造注入链路成立、且所有 {@code @Value}
 * 在<b>没有任何外部配置</b>时都能用内置默认值解析成功——否则应用启动即失败，
 * 而本项目没有 Spring Boot 上下文测试可以提前暴露这类问题。
 */
class ContextGuardWiringTest {

    @Test
    void shouldWireGuardBeansWithDefaultsOnly() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PropertySourcesPlaceholderConfigurer.class,
                    ToolOutputArtifactStore.class,
                    ToolOutputGuard.class,
                    ToolOutputReadTool.class,
                    ObservationElider.class,
                    LoopBreakerFactory.class);
            context.refresh();

            assertThat(context.getBean(ToolOutputGuard.class)).isNotNull();
            assertThat(context.getBean(ToolOutputReadTool.class)).isNotNull();
            assertThat(context.getBean(ObservationElider.class)).isNotNull();

            // 工厂每次都产出独立实例：护栏计数是单次运行内状态，绝不能共享
            LoopBreakerFactory factory = context.getBean(LoopBreakerFactory.class);
            LoopBreaker first = factory.create();
            LoopBreaker second = factory.create();
            assertThat(first).isNotSameAs(second);

            // 默认阈值可用：4000 字符以内不截断
            ToolOutputGuard guard = context.getBean(ToolOutputGuard.class);
            String small = "{\"success\":true}";
            assertThat(guard.guard("task-1", "executeSql", small)).isSameAs(small);
            assertThat(guard.guard("task-1", "executeSql", "x".repeat(5000))).isNotNull();
        }
    }
}
