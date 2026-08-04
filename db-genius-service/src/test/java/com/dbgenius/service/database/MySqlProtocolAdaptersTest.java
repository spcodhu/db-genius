package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 协议兼容系适配器（MariaDB/TiDB/Doris/StarRocks/OceanBase）单元测试。
 *
 * <p>这五个适配器直接继承 {@link MySqlAdapter}，行为应与 MySQL 完全一致，
 * 仅类型标识不同；本测试锁定「类型正确 + 关键行为未漂移」。</p>
 */
class MySqlProtocolAdaptersTest {

    /** 各适配器期望的类型标识 */
    private final Map<MySqlAdapter, DbType> adapters = Map.of(
            new MariaDbAdapter(), DbType.MARIADB,
            new TidbAdapter(), DbType.TIDB,
            new DorisAdapter(), DbType.DORIS,
            new StarRocksAdapter(), DbType.STARROCKS,
            new OceanBaseAdapter(), DbType.OCEANBASE
    );

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
    void getType与各自类型标识一致() {
        adapters.forEach((adapter, type) -> assertEquals(type, adapter.getType()));
    }

    @Test
    void URL模板继承MySQL与现网一致() {
        adapters.forEach((adapter, type) ->
                assertEquals("jdbc:mysql://localhost:3306/testdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        adapter.buildJdbcUrl(config()), type.getCode() + " URL 模板漂移"));
    }

    @Test
    void 标识符继承MySQL反引号() {
        adapters.forEach((adapter, type) ->
                assertEquals("`users`", adapter.quoteIdentifier("users"), type.getCode() + " 引号漂移"));
    }

    @Test
    void 校验继承MySQL完整必填项() {
        adapters.forEach((adapter, type) -> {
            assertDoesNotThrow(() -> adapter.validateRequest(validRequest()));
            DbConfigRequest request = validRequest();
            request.setPassword(null);
            assertThrows(BusinessException.class, () -> adapter.validateRequest(request),
                    type.getCode() + " 缺密码未拦截");
        });
    }

    @Test
    void 只读判断继承MySQL规则() {
        adapters.forEach((adapter, type) ->
                assertTrue(adapter.isReadOnlyStatement("SELECT * FROM t"), type.getCode() + " 只读判断漂移"));
    }
}
