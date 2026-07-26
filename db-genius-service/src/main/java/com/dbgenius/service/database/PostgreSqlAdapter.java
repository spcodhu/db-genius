package com.dbgenius.service.database;

import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 适配器（策略模式 Strategy 之具体策略 + 适配器模式 Adapter）。
 *
 * <p>与 MySQL 的关键差异：PG 的用户表位于 schema（默认 public）之下，
 * 元数据查询必须传 schemaPattern，否则 getTables/getColumns 会扫到
 * 系统 catalog 或漏掉用户表；标识符使用双引号（SQL 标准）。</p>
 */
@Component
public class PostgreSqlAdapter extends AbstractJdbcAdapter {

    @Override
    public DbType getType() {
        return DbType.POSTGRESQL;
    }

    @Override
    protected String buildJdbcUrl(DbConfig config) {
        return "jdbc:postgresql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDbName();
    }

    /**
     * PG 的表在 schema 下，与 MySQL 的「catalog=dbName 且无 schema」定位方式不同，
     * 这里固定为用户表默认所在的 public schema。
     */
    @Override
    protected String schemaPattern(DbConfig config) {
        return "public";
    }

    /** PG 标识符使用双引号，名字内嵌的双引号按 SQL 标准双写转义。 */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** PostgreSQL 需要完整的连接与账密信息。 */
    @Override
    public void validateRequest(DbConfigRequest request) {
        requireNonBlank(request.getHost(), "host");
        requireNonBlank(request.getPort(), "port");
        requireNonBlank(request.getDbName(), "dbName");
        requireNonBlank(request.getUsername(), "username");
        requireNonBlank(request.getPassword(), "password");
    }
}
