package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OracleAdapter 单元测试：service name URL、catalog/schema 定位、双引号转义、
 * 校验正反例、含 WITH 前缀的只读判断。
 */
class OracleAdapterTest {

    private final OracleAdapter adapter = new OracleAdapter();

    private DbConfig config() {
        DbConfig config = new DbConfig();
        config.setHost("localhost");
        config.setPort(1521);
        config.setDbName("ORCLPDB1");
        config.setUsername("scott");
        return config;
    }

    private DbConfigRequest validRequest() {
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        request.setHost("localhost");
        request.setPort(1521);
        request.setDbName("ORCLPDB1");
        request.setUsername("scott");
        request.setPassword("tiger");
        return request;
    }

    @Test
    void getType为ORACLE() {
        assertEquals(DbType.ORACLE, adapter.getType());
    }

    @Test
    void URL为serviceName形式() {
        assertEquals("jdbc:oracle:thin:@//localhost:1521/ORCLPDB1", adapter.buildJdbcUrl(config()));
    }

    @Test
    void 元数据定位catalog为null且schema为用户名大写() {
        DbConfig config = config();
        assertNull(adapter.catalog(config));
        assertEquals("SCOTT", adapter.schemaPattern(config));
    }

    @Test
    void 标识符使用双引号并转义内嵌引号() {
        assertEquals("\"USERS\"", adapter.quoteIdentifier("USERS"));
        assertEquals("\"we\"\"ird\"", adapter.quoteIdentifier("we\"ird"));
    }

    @Test
    void 校验通过_完整请求() {
        assertDoesNotThrow(() -> adapter.validateRequest(validRequest()));
    }

    @Test
    void 校验失败_缺host抛400() {
        DbConfigRequest request = validRequest();
        request.setHost(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    void 校验失败_缺密码抛400() {
        DbConfigRequest request = validRequest();
        request.setPassword("  ");
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }

    @Test
    void 只读判断_SELECT与WITH前缀() {
        assertTrue(adapter.isReadOnlyStatement("SELECT * FROM t"));
        assertTrue(adapter.isReadOnlyStatement("  select id from t"));
        // CTE：默认前缀规则会误判，本适配器必须放行
        assertTrue(adapter.isReadOnlyStatement("WITH x AS (SELECT 1 FROM dual) SELECT * FROM x"));
        assertTrue(adapter.isReadOnlyStatement("EXPLAIN PLAN FOR SELECT 1"));
    }

    @Test
    void 只读判断_写语句与空值() {
        assertFalse(adapter.isReadOnlyStatement("INSERT INTO t VALUES (1)"));
        assertFalse(adapter.isReadOnlyStatement("UPDATE t SET a = 1"));
        assertFalse(adapter.isReadOnlyStatement(null));
        assertFalse(adapter.isReadOnlyStatement("   "));
    }
}
