package com.dbgenius.service.database;

import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Oracle 适配器（策略模式 Strategy 之具体策略 + 适配器模式 Adapter）。
 *
 * <p>与 MySQL/PostgreSQL 的关键差异：
 * ① URL 使用 service name 形式 {@code jdbc:oracle:thin:@//host:port/{dbName}}
 * （SID 形式的 {@code :SID} 写法暂不支持）；② Oracle 无 catalog 概念，
 * 用户表位于与用户名同名的 schema 下，元数据定位用
 * {@code schemaPattern = 用户名大写}（Oracle 未加引号的标识符一律大写存储）；
 * ③ 标识符使用双引号；④ 只读判断补充 {@code WITH}（CTE）前缀。</p>
 */
@Component
public class OracleAdapter extends AbstractJdbcAdapter {

    @Override
    public DbType getType() {
        return DbType.ORACLE;
    }

    /**
     * service name 形式 URL：dbName 字段作为 service name。
     */
    @Override
    protected String buildJdbcUrl(DbConfig config) {
        return "jdbc:oracle:thin:@//" + config.getHost() + ":" + config.getPort() + "/" + config.getDbName();
    }

    /** Oracle 无 catalog 概念，必须返回 null（否则驱动会忽略元数据查询条件）。 */
    @Override
    protected String catalog(DbConfig config) {
        return null;
    }

    /** 用户表在与用户名同名的 schema 下；未加引号创建的标识符在 Oracle 中均为大写。 */
    @Override
    protected String schemaPattern(DbConfig config) {
        return config.getUsername().toUpperCase(Locale.ROOT);
    }

    /** Oracle 标识符使用双引号，名字内嵌的双引号按 SQL 标准双写转义。 */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** 补充 WITH（CTE）前缀：Oracle 常用 WITH ... SELECT，默认前缀规则会误判为写操作。 */
    @Override
    public boolean isReadOnlyStatement(String statement) {
        if (super.isReadOnlyStatement(statement)) {
            return true;
        }
        return statement != null && statement.trim().toUpperCase().startsWith("WITH");
    }

    /** Oracle 需要完整的连接与账密信息。 */
    @Override
    public void validateRequest(DbConfigRequest request) {
        requireNonBlank(request.getHost(), "host");
        requireNonBlank(request.getPort(), "port");
        requireNonBlank(request.getDbName(), "dbName (service name)");
        requireNonBlank(request.getUsername(), "username");
        requireNonBlank(request.getPassword(), "password");
    }
}
