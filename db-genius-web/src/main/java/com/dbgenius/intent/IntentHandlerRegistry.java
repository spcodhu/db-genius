package com.dbgenius.intent;

import com.dbgenius.agent.intent.IntentHandler;
import com.dbgenius.model.enums.IntentType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 意图处理器注册表，自动收集 Spring IoC 容器中的所有 Handler
 */
@Component
public class IntentHandlerRegistry {

    private final Map<IntentType, IntentHandler> handlers;

    public IntentHandlerRegistry(List<IntentHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(IntentHandler::supportedIntent, Function.identity()));
    }

    public IntentHandler getHandler(IntentType type) {
        IntentHandler handler = handlers.get(type);
        if (handler == null) {
            return handlers.get(IntentType.SIMPLE_CHAT);
        }
        return handler;
    }

    public List<IntentType> supportedIntents() {
        return List.copyOf(handlers.keySet());
    }
}
