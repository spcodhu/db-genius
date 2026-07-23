package com.dbgenius.intent;

import com.dbgenius.agent.intent.IntentHandler;
import com.dbgenius.model.enums.IntentType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 意图处理器注册表（策略模式中的策略容器）。
 *
 * <p>启动时自动收集 Spring IoC 容器中所有 {@link IntentHandler} Bean，
 * 按其支持的 {@link IntentType} 建立映射，供 IntentRouter 在运行时按意图查找并分派。
 * 新增一种意图只需新增对应的 Handler Bean，无需改动路由代码。
 */
@Component
public class IntentHandlerRegistry {

    /** 意图类型 → 处理器 的映射表，构造后即不可变 */
    private final Map<IntentType, IntentHandler> handlers;

    /**
     * 应用启动时由 Spring 调用，只执行一次。
     *
     * <p>Spring 的集合注入会把容器内所有 {@link IntentHandler} Bean 组成 List 传入，
     * 以每个 Handler 支持的意图为 key、Handler 自身为 value 构建映射表。
     * 注意：同一个 {@link IntentType} 只能有一个实现，否则 toMap 遇到重复 key 会在启动时报错。
     *
     * @param handlerList 容器中所有的意图处理器 Bean
     */
    public IntentHandlerRegistry(List<IntentHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(IntentHandler::supportedIntent, Function.identity()));
    }

    /**
     * 根据意图类型获取对应的处理器，每次处理聊天请求时由 IntentRouter 调用。
     *
     * @param type 已分类出的意图类型
     * @return 对应的处理器；若该意图未注册处理器，则兜底返回 {@link IntentType#SIMPLE_CHAT} 的处理器
     */
    public IntentHandler getHandler(IntentType type) {
        IntentHandler handler = handlers.get(type);
        if (handler == null) {
            return handlers.get(IntentType.SIMPLE_CHAT);
        }
        return handler;
    }

    /**
     * 返回当前已注册的所有意图类型（返回不可变副本，防止外部修改内部状态）。
     */
    public List<IntentType> supportedIntents() {
        return List.copyOf(handlers.keySet());
    }
}
