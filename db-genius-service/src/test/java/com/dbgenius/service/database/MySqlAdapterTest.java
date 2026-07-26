package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySqlAdapter 单元测试：URL 模板、元数据定位参数、标识符引号、校验正反例、只读判断。
 */
class MySqlAdapterTest {

    private final MySqlAdapter adapter = new MySqlAdapter();

    private DbConfig config() {
        DbConfig config = new DbConfig();
        config.setHost("localhost");
        config.setPort(3306);
        config.setDbName("testdb");
        config.setUsername("root");
        return config;
    }

    private DbConfigRequest validRequest() {
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        request.setHost("localhost");
        request.setPort(3306);
        request.setDbName("testdb");
        request.setUsername("root");
        request.setPassword("123456");
        return request;
    }

    @Test
    void getType为MYSQL() {
        assertEquals(DbType.MYSQL, adapter.getType());
    }

    @Test
    void URL模板与现网保持一致() {
        // 该 URL 模板含现网在用的全部参数，任何漂移都应被本测试拦住
        assertEquals("jdbc:mysql://localhost:3306/testdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                adapter.buildJdbcUrl(config()));
    }

    @Test
    void 元数据定位参数为catalog等于dbName且schema为null() {
        DbConfig config = config();
        assertEquals("testdb", adapter.catalog(config));
        assertNull(adapter.schemaPattern(config));
    }

    @Test
    void 标识符使用反引号() {
        assertEquals("`users`", adapter.quoteIdentifier("users"));
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
    void 校验失败_缺port抛400() {
        DbConfigRequest request = validRequest();
        request.setPort(null);
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }

    @Test
    void 校验失败_密码空白抛400() {
        DbConfigRequest request = validRequest();
        request.setPassword("  ");
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }

    @Test
    void 只读判断_SELECT大小写与SHOW_DESC_EXPLAIN() {
        assertTrue(adapter.isReadOnlyStatement("SELECT * FROM t"));
        assertTrue(adapter.isReadOnlyStatement("  select id from t"));
        assertTrue(adapter.isReadOnlyStatement("SHOW TABLES"));
        assertTrue(adapter.isReadOnlyStatement("DESC users"));
        assertTrue(adapter.isReadOnlyStatement("EXPLAIN SELECT 1"));
    }

    @Test
    void 只读判断_写语句与空值() {
        assertFalse(adapter.isReadOnlyStatement("INSERT INTO t VALUES (1)"));
        assertFalse(adapter.isReadOnlyStatement("UPDATE t SET a = 1"));
        assertFalse(adapter.isReadOnlyStatement(null));
        assertFalse(adapter.isReadOnlyStatement("   "));
    }
}
