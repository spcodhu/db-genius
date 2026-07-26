package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DatabaseAdapterRegistry 单元测试：注册、按编码/枚举查找、重复注册与未知类型的快速失败。
 */
class DatabaseAdapterRegistryTest {

    private DatabaseAdapterRegistry registry() {
        return new DatabaseAdapterRegistry(List.of(
                new MySqlAdapter(), new PostgreSqlAdapter(), new SQLiteAdapter(), new MongoDbAdapter()));
    }

    @Test
    void 按类型编码查找适配器() {
        DatabaseAdapterRegistry registry = registry();
        assertInstanceOf(MySqlAdapter.class, registry.getAdapter("mysql"));
        assertInstanceOf(PostgreSqlAdapter.class, registry.getAdapter("postgresql"));
        assertInstanceOf(SQLiteAdapter.class, registry.getAdapter("sqlite"));
        assertInstanceOf(MongoDbAdapter.class, registry.getAdapter("mongodb"));
    }

    @Test
    void 编码大小写不敏感且空值回退mysql() {
        DatabaseAdapterRegistry registry = registry();
        assertInstanceOf(PostgreSqlAdapter.class, registry.getAdapter("PostgreSQL"));
        // 历史兼容：null/空白统一回退 mysql
        assertInstanceOf(MySqlAdapter.class, registry.getAdapter((String) null));
        assertInstanceOf(MySqlAdapter.class, registry.getAdapter("  "));
    }

    @Test
    void 按枚举查找适配器() {
        DatabaseAdapterRegistry registry = registry();
        assertInstanceOf(MongoDbAdapter.class, registry.getAdapter(DbType.MONGODB));
    }

    @Test
    void 重复注册同一类型在启动期快速失败() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DatabaseAdapterRegistry(List.of(new MySqlAdapter(), new MySqlAdapter())));
        assertEquals("Duplicate DatabaseAdapter for type: MYSQL", ex.getMessage());
    }

    @Test
    void 未知类型编码抛业务异常() {
        BusinessException ex = assertThrows(BusinessException.class, () -> registry().getAdapter("oracle"));
        assertEquals(400, ex.getCode());
    }
}
