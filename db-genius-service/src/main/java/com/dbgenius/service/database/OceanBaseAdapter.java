package com.dbgenius.service.database;

import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * OceanBase 适配器。
 *
 * <p>OceanBase 的 MySQL 模式兼容 MySQL 协议（默认端口 2881），
 * 可直接使用 MySQL JDBC 驱动连接，方言细节与 {@link MySqlAdapter}
 * 一致，仅声明独立的类型标识。（Oracle 模式租户不在本适配器范围内。）</p>
 */
@Component
public class OceanBaseAdapter extends MySqlAdapter {

    @Override
    public DbType getType() {
        return DbType.OCEANBASE;
    }
}
