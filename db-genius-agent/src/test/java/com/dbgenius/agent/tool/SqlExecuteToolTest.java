package com.dbgenius.agent.tool;

import com.dbgenius.agent.metrics.AgentMetrics;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.database.AbstractJdbcAdapter;
import com.dbgenius.service.database.DatabaseAdapter;
import com.dbgenius.service.database.DatabaseAdapterRegistry;
import com.dbgenius.service.database.MongoDbAdapter;
import com.dbgenius.trial.TrialGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SqlExecuteTool 单元测试：覆盖安全红线硬性拦截、试用版只读限制（方言感知）、
 * 以及 JDBC / MongoDB 两条执行分派路径。
 */
class SqlExecuteToolTest {

    private static final String ENCRYPT_KEY = "0123456789abcdef0123456789abcdef";

    private final DbConfigService dbConfigService = mock(DbConfigService.class);
    private final TrialGuard trialGuard = mock(TrialGuard.class);
    private final DatabaseAdapterRegistry adapterRegistry = mock(DatabaseAdapterRegistry.class);
    private final AgentMetrics agentMetrics = mock(AgentMetrics.class);
    private final SqlExecuteTool tool = new SqlExecuteTool(dbConfigService, trialGuard, adapterRegistry, agentMetrics);

    /** 构造一个已通过连通性验证的库配置。 */
    private DbConfig connectedConfig(String dbType) {
        DbConfig config = new DbConfig();
        config.setId(1L);
        config.setDbType(dbType);
        config.setHost("localhost");
        config.setPort(3306);
        config.setDbName("testdb");
        config.setUsername("root");
        config.setStatus(DbConfigStatus.CONNECTED);
        return config;
    }

    /** 通过反射注入 @Value 字段（单元测试无 Spring 容器）。 */
    private void injectEncryptKey() throws Exception {
        Field field = SqlExecuteTool.class.getDeclaredField("encryptKey");
        field.setAccessible(true);
        field.set(tool, ENCRYPT_KEY);
    }

    @Test
    void shouldRejectDestructiveSqlEvenWhenUserExplicitlyAsks() {
        // 安全红线：DROP 即使用户明确要求也不放行，且不进入 trial 判断与任何执行逻辑
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(dbConfigService.getById(1L)).thenReturn(connectedConfig("mysql"));
        when(adapterRegistry.getAdapter("mysql")).thenReturn(adapter);

        String result = tool.executeSql(1L, "DROP TABLE users");

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("安全红线"));
        verify(adapter, never()).isReadOnlyStatement(any());
    }

    @Test
    void shouldRejectMongoDropCommand() {
        // MongoDB 侧 drop 操作同样被硬性拦截，绝不委托给适配器执行
        MongoDbAdapter mongo = mock(MongoDbAdapter.class);
        when(dbConfigService.getById(1L)).thenReturn(connectedConfig("mongodb"));
        when(adapterRegistry.getAdapter("mongodb")).thenReturn(mongo);

        String result = tool.executeSql(1L, "db.users.drop()");

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("安全红线"));
        verify(mongo, never()).executeCommand(any(), any(), any());
    }

    @Test
    void shouldRejectNonReadOnlySqlInTrialMode() {
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(trialGuard.isTrialMode()).thenReturn(true);
        when(dbConfigService.getById(1L)).thenReturn(connectedConfig("mysql"));
        when(adapterRegistry.getAdapter("mysql")).thenReturn(adapter);
        when(adapter.isReadOnlyStatement("INSERT INTO users VALUES (1)")).thenReturn(false);

        String result = tool.executeSql(1L, "INSERT INTO users VALUES (1)");

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("试用版仅支持只读查询"));
    }

    @Test
    void shouldAllowSelectSqlInTrialMode() {
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(trialGuard.isTrialMode()).thenReturn(true);
        when(dbConfigService.getById(1L)).thenReturn(connectedConfig("mysql"));
        when(adapterRegistry.getAdapter("mysql")).thenReturn(adapter);
        when(adapter.isReadOnlyStatement("SELECT * FROM users")).thenReturn(true);

        String result = tool.executeSql(1L, "SELECT * FROM users");

        // 通过只读校验后继续往下走（此处因无真实加密密钥而失败），但绝不是被试用版拦截
        assertFalse(result.contains("试用版仅支持只读查询"));
    }

    @Test
    void shouldIgnoreReadOnlyCheckWhenTrialDisabled() {
        DatabaseAdapter adapter = mock(DatabaseAdapter.class);
        when(trialGuard.isTrialMode()).thenReturn(false);
        when(dbConfigService.getById(1L)).thenReturn(connectedConfig("mysql"));
        when(adapterRegistry.getAdapter("mysql")).thenReturn(adapter);

        String result = tool.executeSql(1L, "DELETE FROM users WHERE id = 1");

        assertFalse(result.contains("试用版仅支持只读查询"));
    }

    @Test
    void shouldDelegateMongoCommandToAdapter() throws Exception {
        injectEncryptKey();
        MongoDbAdapter mongo = mock(MongoDbAdapter.class);
        DbConfig config = connectedConfig("mongodb");
        config.setPasswordEncrypted(AesUtil.encrypt("secret", ENCRYPT_KEY));
        when(trialGuard.isTrialMode()).thenReturn(false);
        when(dbConfigService.getById(1L)).thenReturn(config);
        when(adapterRegistry.getAdapter("mongodb")).thenReturn(mongo);
        when(mongo.executeCommand(eq(config), eq("secret"), anyString())).thenReturn("[{\"name\":\"alice\"}]");

        String command = "{\"collection\":\"users\",\"operation\":\"find\",\"filter\":{}}";
        String result = tool.executeSql(1L, command);

        assertTrue(result.contains("\"success\":true"));
        assertTrue(result.contains("alice"));
        verify(mongo).executeCommand(config, "secret", command);
    }

    @Test
    void shouldDispatchToJdbcAdapterAndReportSqlError() throws Exception {
        injectEncryptKey();
        AbstractJdbcAdapter jdbc = mock(AbstractJdbcAdapter.class);
        DbConfig config = connectedConfig("mysql");
        config.setPasswordEncrypted(AesUtil.encrypt("secret", ENCRYPT_KEY));
        when(trialGuard.isTrialMode()).thenReturn(false);
        when(dbConfigService.getById(1L)).thenReturn(config);
        when(adapterRegistry.getAdapter("mysql")).thenReturn(jdbc);
        when(jdbc.isReadOnlyStatement(anyString())).thenReturn(true);
        when(jdbc.openConnection(config, "secret")).thenThrow(new SQLException("connection refused"));

        String result = tool.executeSql(1L, "SELECT * FROM users");

        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("connection refused"));
    }
}
