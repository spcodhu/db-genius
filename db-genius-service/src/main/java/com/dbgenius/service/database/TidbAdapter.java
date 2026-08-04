package com.dbgenius.service.database;

import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * TiDB 适配器。
 *
 * <p>TiDB 是兼容 MySQL 协议的分布式 HTAP 数据库（默认端口 4000），
 * 可直接使用 MySQL JDBC 驱动连接，方言细节与 {@link MySqlAdapter}
 * 一致，仅声明独立的类型标识。</p>
 */
@Component
public class TidbAdapter extends MySqlAdapter {

    @Override
    public DbType getType() {
        return DbType.TIDB;
    }
}
