package com.dbgenius.trial;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * {@link TrialDeny} 注解的 AOP 切面：方法进入前执行试用版拒绝校验，
 * 业务方法内不再需要手写 {@code trialGuard.denyIfTrial(...)}。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class TrialGuardAspect {

    private final TrialGuard trialGuard;

    @Before("@annotation(trialDeny)")
    public void denyIfTrial(TrialDeny trialDeny) {
        trialGuard.denyIfTrial(trialDeny.value());
    }
}
