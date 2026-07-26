package com.dbgenius.common.util;

import com.dbgenius.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlSafetyGuard 单元测试：DROP/TRUNCATE 全形态拦截、注释与字符串字面量不误伤、
 * MongoDB drop/dropDatabase 拦截与查询命令放行。
 */
class SqlSafetyGuardTest {

    @Test
    void 拦截DROP的各种形态() {
        assertTrue(SqlSafetyGuard.isDestructive("DROP TABLE users"));
        assertTrue(SqlSafetyGuard.isDestructive("DROP DATABASE testdb"));
        // 小写同样拦截
        assertTrue(SqlSafetyGuard.isDestructive("drop table users"));
        assertTrue(SqlSafetyGuard.isDestructive("ALTER TABLE t DROP COLUMN c"));
    }

    @Test
    void 拦截TRUNCATE() {
        assertTrue(SqlSafetyGuard.isDestructive("TRUNCATE TABLE t"));
        assertTrue(SqlSafetyGuard.isDestructive("truncate t"));
    }

    @Test
    void 前导注释后的DROP仍被拦截() {
        assertTrue(SqlSafetyGuard.isDestructive("--  harmless comment\nDROP TABLE users"));
        assertTrue(SqlSafetyGuard.isDestructive("/* block comment */ DROP TABLE users"));
    }

    @Test
    void 字符串字面量中的drop不误伤() {
        assertFalse(SqlSafetyGuard.isDestructive("SELECT 'drop' FROM t"));
        assertFalse(SqlSafetyGuard.isDestructive("INSERT INTO t VALUES ('DROP TABLE x')"));
        assertFalse(SqlSafetyGuard.isDestructive("SELECT \"truncate\" FROM t"));
    }

    @Test
    void SELECT等普通语句放行() {
        assertFalse(SqlSafetyGuard.isDestructive("SELECT * FROM users"));
        assertFalse(SqlSafetyGuard.isDestructive("SELECT dropped_count FROM stats"));
        assertFalse(SqlSafetyGuard.isDestructive(null));
        assertFalse(SqlSafetyGuard.isDestructive("   "));
        assertDoesNotThrow(() -> SqlSafetyGuard.assertSafe("SELECT * FROM users"));
    }

    @Test
    void assertSafe命中红线抛403() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SqlSafetyGuard.assertSafe("DROP TABLE users"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void Mongo的drop与dropDatabase被拦截() {
        // 命令名形态（guard 会剥离双引号内容，JSON 形态的检测由适配器层的操作白名单兜底）
        assertTrue(SqlSafetyGuard.isDestructiveMongoCommand("drop"));
        assertTrue(SqlSafetyGuard.isDestructiveMongoCommand("dropDatabase"));
        assertTrue(SqlSafetyGuard.isDestructiveMongoCommand("db.users.drop()"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SqlSafetyGuard.assertMongoCommandSafe("dropDatabase"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void Mongo查询命令放行() {
        assertFalse(SqlSafetyGuard.isDestructiveMongoCommand(
                "{\"collection\":\"users\",\"operation\":\"find\",\"filter\":{\"age\":1}}"));
        assertDoesNotThrow(() -> SqlSafetyGuard.assertMongoCommandSafe(
                "{\"collection\":\"users\",\"operation\":\"find\"}"));
    }
}
