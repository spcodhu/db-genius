package com.dbgenius.trial;

import com.dbgenius.common.exception.TrialBusinessException;
import com.dbgenius.model.entity.DbConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrialGuardTest {

    @Test
    void shouldAllowOperationsWhenTrialDisabled() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(false);
        TrialGuard guard = new TrialGuard(properties);

        assertFalse(guard.isTrialMode());
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) guard::denyIfTrial);
        assertDoesNotThrow(() -> guard.denyIfTrial("any"));
        assertDoesNotThrow(() -> guard.denyIfTrialBuiltin(new DbConfig()));
    }

    @Test
    void shouldDenyAllOperationsWhenTrialEnabled() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(true);
        TrialGuard guard = new TrialGuard(properties);

        assertTrue(guard.isTrialMode());
        assertThrows(TrialBusinessException.class, guard::denyIfTrial);
        assertThrows(TrialBusinessException.class, () -> guard.denyIfTrial("custom"));
    }

    @Test
    void shouldDenyOnlyBuiltinConfigInTrialMode() {
        TrialProperties properties = new TrialProperties();
        properties.setEnabled(true);
        TrialGuard guard = new TrialGuard(properties);

        DbConfig normalConfig = new DbConfig();
        normalConfig.setBuiltin(false);
        assertDoesNotThrow(() -> guard.denyIfTrialBuiltin(normalConfig));

        DbConfig builtinConfig = new DbConfig();
        builtinConfig.setBuiltin(true);
        assertThrows(TrialBusinessException.class, () -> guard.denyIfTrialBuiltin(builtinConfig));
    }

    @Test
    void shouldRecognizeBuiltinConfig() {
        TrialProperties properties = new TrialProperties();
        TrialGuard guard = new TrialGuard(properties);

        DbConfig config = new DbConfig();
        config.setBuiltin(true);
        assertTrue(guard.isBuiltin(config));

        config.setBuiltin(false);
        assertFalse(guard.isBuiltin(config));

        assertFalse(guard.isBuiltin(null));
    }
}
