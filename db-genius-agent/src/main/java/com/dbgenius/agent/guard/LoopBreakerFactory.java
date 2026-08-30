package com.dbgenius.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link LoopBreaker} 的工厂：护栏计数是<b>单次运行内</b>的状态，绝不能做成共享单例，
 * 因此由本工厂按配置为每个 Agent 运行创建一份独立实例。
 */
@Component
public class LoopBreakerFactory {

    @Value("${db-genius.context.loop-breaker.repeat-threshold:3}")
    private int repeatThreshold = 3;

    @Value("${db-genius.context.loop-breaker.hard-stop-repeat:5}")
    private int hardStopRepeat = 5;

    @Value("${db-genius.context.loop-breaker.truncation-hint-threshold:3}")
    private int truncationHintThreshold = 3;

    public LoopBreaker create() {
        return new LoopBreaker(repeatThreshold, hardStopRepeat, truncationHintThreshold);
    }
}
