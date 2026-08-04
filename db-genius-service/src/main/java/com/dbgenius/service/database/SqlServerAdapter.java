package com.dbgenius.service.database;

import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * SQL Server 适配器（策略模式 Strategy 之具体策略 + 适配器模式 Adapter）。
 *
 * <p>与 MySQL/PostgreSQL 的关键差异：
 * ① URL 为 {@code jdbc:sqlserver://host:port;databaseName=xxx} 分号参数形式，
 * {@code encrypt=true;trustServerCertificate=true} 兼容自签证书的内网实例
 * （mssql-jdbc 10.2+ 默认强制加密，不配对会握手失败）；② 元数据定位用
 * catalog=dbName + schemaPattern=dbo（仅覆盖默认 dbo schema，自定义 schema
 * 的表暂不在文档抽取范围内）；③ 标识符使用方括号；④ 只读判断补充
 * {@code WITH}（CTE）前缀。</p>
 */
@Component
public class SqlServerAdapter extends AbstractJdbcAdapter {

    @Override
    public DbType getType() {
        return DbType.SQLSERVER;
    }

    @Override
    protected String buildJdbcUrl(DbConfig config) {
        return "jdbc:sqlserver://" + config.getHost() + ":" + config.getPort()
                + ";databaseName=" + config.getDbName()
                + ";encrypt=true;trustServerCertificate=true";
    }

    /** SQL Server 元数据定位用 catalog=dbName。 */
    @Override
    protected String catalog(DbConfig config) {
        return config.getDbName();
    }

    /** 仅抽取默认 dbo schema 下的用户表（自定义 schema 暂不支持）。 */
    @Override
    protected String schemaPattern(DbConfig config) {
        return "dbo";
    }

    /** SQL Server 标识符使用方括号，名字内嵌的右方括号双写转义。 */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    /** 补充 WITH（CTE）前缀：SQL Server 常用 WITH ... SELECT，默认前缀规则会误判为写操作。 */
    @Override
    public boolean isReadOnlyStatement(String statement) {
        if (super.isReadOnlyStatement(statement)) {
            return true;
        }
        return statement != null && statement.trim().toUpperCase().startsWith("WITH");
    }

    /** SQL Server 需要完整的连接与账密信息。 */
    @Override
    public void validateRequest(DbConfigRequest request) {
        requireNonBlank(request.getHost(), "host");
        requireNonBlank(request.getPort(), "port");
        requireNonBlank(request.getDbName(), "dbName");
        requireNonBlank(request.getUsername(), "username");
        requireNonBlank(request.getPassword(), "password");
    }
}
