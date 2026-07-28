package com.dbgenius.trial;

import com.dbgenius.common.exception.TrialBusinessException;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrialGuardAspect 单元测试：直接调用 advice 方法验证 @TrialDeny 的拒绝/放行为。
 */
class TrialGuardAspectTest {

    private TrialDeny trialDeny(String message) {
        return new TrialDeny() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return TrialDeny.class;
            }

            @Override
            public String value() {
                return message;
            }
        };
    }

    @Test
    void shouldDenyWhenTrialEnabled() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(true);
        TrialGuardAspect aspect = new TrialGuardAspect(new TrialGuard(properties));

        TrialBusinessException e = assertThrows(TrialBusinessException.class,
                () -> aspect.denyIfTrial(trialDeny("试用版暂不支持此操作")));
        assertEquals("试用版暂不支持此操作", e.getMessage());
    }

    @Test
    void shouldAllowWhenTrialDisabled() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(false);
        TrialGuardAspect aspect = new TrialGuardAspect(new TrialGuard(properties));

        assertDoesNotThrow(() -> aspect.denyIfTrial(trialDeny("试用版暂不支持此操作")));
    }
}
