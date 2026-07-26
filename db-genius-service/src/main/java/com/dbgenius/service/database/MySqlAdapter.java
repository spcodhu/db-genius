package com.dbgenius.service.database;

import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * MySQL 适配器（策略模式 Strategy 之具体策略 + 适配器模式 Adapter）。
 *
 * <p>继承 {@link AbstractJdbcAdapter} 模板，仅声明 MySQL 方言细节：
 * URL 模板保持与现网完全一致（含 useSSL=false 等参数，保证行为无回归），
 * catalog 定位用 dbName、无 schema 概念、标识符用反引号。</p>
 */
@Component
public class MySqlAdapter extends AbstractJdbcAdapter {

    @Override
    public DbType getType() {
        return DbType.MYSQL;
    }

    /**
     * URL 与现网逐字符一致，任何改动都可能影响存量连接行为，勿随意调整。
     */
    @Override
    protected String buildJdbcUrl(DbConfig config) {
        return "jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDbName()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    /** MySQL 元数据定位用 catalog=dbName，schema 恒为 null。 */
    @Override
    protected String schemaPattern(DbConfig config) {
        return null;
    }

    /** MySQL 标识符使用反引号。 */
    @Override
    protected String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    /** MySQL 需要完整的连接与账密信息。 */
    @Override
    public void validateRequest(DbConfigRequest request) {
        requireNonBlank(request.getHost(), "host");
        requireNonBlank(request.getPort(), "port");
        requireNonBlank(request.getDbName(), "dbName");
        requireNonBlank(request.getUsername(), "username");
        requireNonBlank(request.getPassword(), "password");
    }
}
