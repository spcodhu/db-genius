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
 * SQLiteAdapter 单元测试：文件路径 URL、无认证、仅需 dbName 的校验、PRAGMA 只读判断。
 */
class SQLiteAdapterTest {

    private final SQLiteAdapter adapter = new SQLiteAdapter();

    @Test
    void getType为SQLITE() {
        assertEquals(DbType.SQLITE, adapter.getType());
    }

    @Test
    void URL中dbName即数据库文件路径() {
        DbConfig config = new DbConfig();
        config.setDbName("/data/app.db");
        assertEquals("jdbc:sqlite:/data/app.db", adapter.buildJdbcUrl(config));
    }

    @Test
    void 嵌入式库无需认证且无catalog和schema概念() {
        DbConfig config = new DbConfig();
        config.setDbName("/data/app.db");
        assertFalse(adapter.needsAuthentication());
        assertNull(adapter.catalog(config));
        assertNull(adapter.schemaPattern(config));
    }

    @Test
    void 标识符使用双引号() {
        assertEquals("\"users\"", adapter.quoteIdentifier("users"));
    }

    @Test
    void 校验通过_仅dbName必填() {
        // host/port/账密对 SQLite 无意义，全部缺失也应通过
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        request.setDbName("/data/app.db");
        assertDoesNotThrow(() -> adapter.validateRequest(request));
    }

    @Test
    void 校验失败_缺dbName抛400() {
        DbConfigRequest request = new DbConfigRequest();
        request.setName("t");
        BusinessException ex = assertThrows(BusinessException.class, () -> adapter.validateRequest(request));
        assertEquals(400, ex.getCode());
    }

    @Test
    void 只读判断_SELECT_EXPLAIN_PRAGMA() {
        assertTrue(adapter.isReadOnlyStatement("SELECT * FROM t"));
        assertTrue(adapter.isReadOnlyStatement("EXPLAIN QUERY PLAN SELECT 1"));
        assertTrue(adapter.isReadOnlyStatement("PRAGMA table_info(users)"));
        assertTrue(adapter.isReadOnlyStatement("  pragma journal_mode"));
    }

    @Test
    void 只读判断_写语句与SHOW() {
        // SQLite 无 SHOW 语法，按非只读处理
        assertFalse(adapter.isReadOnlyStatement("SHOW TABLES"));
        assertFalse(adapter.isReadOnlyStatement("INSERT INTO t VALUES (1)"));
        assertFalse(adapter.isReadOnlyStatement(null));
    }
}
