package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSqlAdapter 单元测试：URL 模板、public schema 定位、双引号转义、校验正反例。
 */
class PostgreSqlAdapterTest {

    private final PostgreSqlAdapter adapter = new PostgreSqlAdapter();

    private DbConfig config() {
        DbConfig config = new DbConfig();
        config.setHost("localhost");
        config.setPort(5432);
        config.setDbName("testdb");
        config.setUsername("postgres");
        return config;
    }

    private DbConfigRequest validRequest() {
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        request.setHost("localhost");
        request.setPort(5432);
        request.setDbName("testdb");
        request.setUsername("postgres");
        request.setPassword("123456");
        return request;
    }

    @Test
    void getType为POSTGRESQL() {
        assertEquals(DbType.POSTGRESQL, adapter.getType());
    }

    @Test
    void URL模板正确() {
        assertEquals("jdbc:postgresql://localhost:5432/testdb", adapter.buildJdbcUrl(config()));
    }

    @Test
    void 元数据定位到public的schema() {
        // PG 的用户表在 schema 下，这是与 MySQL 的关键差异
        DbConfig config = config();
        assertEquals("testdb", adapter.catalog(config));
        assertEquals("public", adapter.schemaPattern(config));
    }

    @Test
    void 标识符使用双引号并转义内嵌引号() {
        assertEquals("\"users\"", adapter.quoteIdentifier("users"));
        // 内嵌双引号按 SQL 标准双写转义
        assertEquals("\"we\"\"ird\"", adapter.quoteIdentifier("we\"ird"));
    }

    @Test
    void 校验通过_完整请求() {
        assertDoesNotThrow(() -> adapter.validateRequest(validRequest()));
    }

    @Test
    void 校验失败_缺username抛400() {
        DbConfigRequest request = validRequest();
        request.setUsername("");
        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    void 校验失败_缺dbName抛400() {
        DbConfigRequest request = validRequest();
        request.setDbName(null);
        assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
    }
}
