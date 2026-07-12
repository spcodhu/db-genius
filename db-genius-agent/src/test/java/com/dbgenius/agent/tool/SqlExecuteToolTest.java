package com.dbgenius.agent.tool;

import com.dbgenius.service.DbConfigService;
import com.dbgenius.trial.TrialGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlExecuteToolTest {

    private final DbConfigService dbConfigService = mock(DbConfigService.class);
    private final TrialGuard trialGuard = mock(TrialGuard.class);
    private final SqlExecuteTool tool = new SqlExecuteTool(dbConfigService, trialGuard);

    @Test
    void shouldRejectNonReadOnlySqlInTrialMode() {
        when(trialGuard.isTrialMode()).thenReturn(true);

        String result = tool.executeSql(1L, "INSERT INTO users VALUES (1)");

        assertTrue(result.contains("false"));
        assertTrue(result.contains("试用版仅支持只读查询"));
        verify(dbConfigService, never()).getById(any());
    }

    @Test
    void shouldAllowSelectSqlInTrialMode() {
        when(trialGuard.isTrialMode()).thenReturn(true);

        String result = tool.executeSql(1L, "SELECT * FROM users");

        // Will fail at DB connection, but should pass the readonly check
        assertTrue(result.contains("Error") || result.contains("false"));
        assertFalse(result.contains("试用版仅支持只读查询"));
    }

    @Test
    void shouldIgnoreReadOnlyCheckWhenTrialDisabled() {
        when(trialGuard.isTrialMode()).thenReturn(false);

        String result = tool.executeSql(1L, "DELETE FROM users WHERE id = 1");

        // Should reach DB config lookup and fail because no config
        assertTrue(result.contains("Error"));
        assertFalse(result.contains("试用版仅支持只读查询"));
    }
}
