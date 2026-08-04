package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlServerAdapter 单元测试：分号参数 URL、catalog/schema 定位、方括号转义、
 * 校验正反例、含 WITH 前缀的只读判断。
 */
class SqlServerAdapterTest {

    private final SqlServerAdapter adapter = new SqlServerAdapter();

    private DbConfig config() {
        DbConfig config = new DbConfig();
        config.setHost("localhost");
        config.setPort(1433);
        config.setDbName("testdb");
        config.setUsername("sa");
        return config;
    }

    private DbConfigRequest validRequest() {
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        request.setHost("localhost");
        request.setPort(1433);
        request.setDbName("testdb");
        request.setUsername("sa");
        request.setPassword("123456");
        return request;
    }

    @Test
    void getType为SQLSERVER() {
        assertEquals(DbType.SQLSERVER, adapter.getType());
    }

    @Test
    void URL为分号参数形式且兼容自签证书() {
        assertEquals("jdbc:sqlserver://localhost:1433;databaseName=testdb;encrypt=true;trustServerCertificate=true",
                adapter.buildJdbcUrl(config()));
    }

    @Test
    void 元数据定位catalog为dbName且schema为dbo() {
        DbConfig config = config();
        assertEquals("testdb", adapter.catalog(config));
        assertEquals("dbo", adapter.schemaPattern(config));
    }

    @Test
    void 标识符使用方括号并转义内嵌右括号() {
        assertEquals("[users]", adapter.quoteIdentifier("users"));
        assertEquals("[we]]ird]", adapter.quoteIdentifier("we]ird"));
    }

    @Test
    void 校验通过_完整请求() {
        assertDoesNotThrow(() -> adapter.validateRequest(validRequest()));
    }

    @Test
    void 校验失败_缺port抛400() {
        DbConfigRequest request = validRequest();
        request.setPort(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    void 校验失败_缺用户名抛400() {
        DbConfigRequest request = validRequest();
        request.setUsername("");
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }

    @Test
    void 只读判断_SELECT与WITH前缀() {
        assertTrue(adapter.isReadOnlyStatement("SELECT * FROM t"));
        assertTrue(adapter.isReadOnlyStatement("  select top 10 id from t"));
        // CTE：默认前缀规则会误判，本适配器必须放行
        assertTrue(adapter.isReadOnlyStatement("WITH x AS (SELECT 1) SELECT * FROM x"));
    }

    @Test
    void 只读判断_写语句与空值() {
        assertFalse(adapter.isReadOnlyStatement("INSERT INTO t VALUES (1)"));
        assertFalse(adapter.isReadOnlyStatement("UPDATE t SET a = 1"));
        assertFalse(adapter.isReadOnlyStatement(null));
        assertFalse(adapter.isReadOnlyStatement("   "));
    }
}
