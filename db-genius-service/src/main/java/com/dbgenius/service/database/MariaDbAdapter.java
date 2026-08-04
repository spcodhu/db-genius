package com.dbgenius.service.database;

import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * MariaDB 适配器。
 *
 * <p>MariaDB 与 MySQL 同源，完全兼容 MySQL 协议与 JDBC URL 格式，
 * 直接复用 {@link MySqlAdapter} 的全部方言细节（URL 模板、反引号、
 * catalog 定位、必填校验），仅声明独立的类型标识。</p>
 */
@Component
public class MariaDbAdapter extends MySqlAdapter {

    @Override
    public DbType getType() {
        return DbType.MARIADB;
    }
}
