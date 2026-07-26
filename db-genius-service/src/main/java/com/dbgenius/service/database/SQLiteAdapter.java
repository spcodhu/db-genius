package com.dbgenius.service.database;

import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * SQLite 适配器（策略模式 Strategy 之具体策略 + 适配器模式 Adapter）。
 *
 * <p>SQLite 是嵌入式数据库：没有 host/port/账密概念，
 * dbName 即数据库文件路径（如 /data/app.db 或 :memory:），
 * 连接时无需认证（{@link #needsAuthentication()} 返回 false）。</p>
 */
@Component
public class SQLiteAdapter extends AbstractJdbcAdapter {

    @Override
    public DbType getType() {
        return DbType.SQLITE;
    }

    /** SQLite 的 dbName 就是数据库文件路径，直接拼进 URL。 */
    @Override
    protected String buildJdbcUrl(DbConfig config) {
        return "jdbc:sqlite:" + config.getDbName();
    }

    /** SQLite 无账密认证，走单参 getConnection(url)。 */
    @Override
    protected boolean needsAuthentication() {
        return false;
    }

    /** SQLite 无 catalog 概念。 */
    @Override
    protected String catalog(DbConfig config) {
        return null;
    }

    /** SQLite 无 schema 概念。 */
    @Override
    protected String schemaPattern(DbConfig config) {
        return null;
    }

    /** SQLite 标识符使用双引号。 */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * SQLite 只需要 dbName（数据库文件路径）；
     * host/port/账密对嵌入式库没有意义，不做校验。
     */
    @Override
    public void validateRequest(DbConfigRequest request) {
        requireNonBlank(request.getDbName(), "dbName");
    }

    /**
     * SQLite 方言的只读判断：不支持 SHOW/DESC，额外放行 PRAGMA（元数据查询）。
     */
    @Override
    public boolean isReadOnlyStatement(String statement) {
        if (statement == null || statement.isBlank()) {
            return false;
        }
        String normalized = statement.trim().toUpperCase();
        return normalized.startsWith("SELECT")
                || normalized.startsWith("EXPLAIN")
                || normalized.startsWith("PRAGMA");
    }
}
