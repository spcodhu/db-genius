package com.dbgenius.trial;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 试用版方法级拒绝注解：标注的方法在试用版模式下直接抛出
 * {@link com.dbgenius.common.exception.TrialBusinessException}（全局异常处理器转 403）。
 *
 * <p><b>生效前提</b>：基于 Spring AOP（{@link TrialGuardAspect}），只对经 Spring 代理的
 * 外部调用生效；同类内部自调用不会触发。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TrialDeny {

    /**
     * 试用版下拒绝时的提示语
     */
    String value();
}
