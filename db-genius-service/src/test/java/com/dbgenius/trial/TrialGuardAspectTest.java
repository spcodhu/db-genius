package com.dbgenius.trial;

import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.exception.TrialBusinessException;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrialGuardAspect 单元测试：直接调用 advice 方法验证 @TrialDeny 的拒绝/放行为。
 */
class TrialGuardAspectTest {

    private TrialDeny trialDeny(ErrorCode errorCode) {
        return new TrialDeny() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return TrialDeny.class;
            }

            @Override
            public ErrorCode value() {
                return errorCode;
            }
        };
    }

    @Test
    void shouldDenyWhenTrialEnabled() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(true);
        TrialGuardAspect aspect = new TrialGuardAspect(new TrialGuard(properties));

        TrialBusinessException e = assertThrows(TrialBusinessException.class,
                () -> aspect.denyIfTrial(trialDeny(ErrorCode.TRIAL_WORKFLOW)));
        assertEquals(ErrorCode.TRIAL_WORKFLOW, e.getErrorCode());
        assertEquals(403, e.getCode());
    }

    @Test
    void shouldAllowWhenTrialDisabled() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(false);
        TrialGuardAspect aspect = new TrialGuardAspect(new TrialGuard(properties));

        assertDoesNotThrow(() -> aspect.denyIfTrial(trialDeny(ErrorCode.TRIAL_WORKFLOW)));
    }
}
