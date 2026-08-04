package com.dbgenius.service.database;

import com.dbgenius.model.enums.DbType;
import org.springframework.stereotype.Component;

/**
 * Apache Doris 适配器。
 *
 * <p>Doris 是兼容 MySQL 协议的分析型 OLAP 数据库（FE 查询端口默认 9030），
 * 可直接使用 MySQL JDBC 驱动连接，元数据经 information_schema 抽取，
 * 方言细节与 {@link MySqlAdapter} 一致，仅声明独立的类型标识。</p>
 */
@Component
public class DorisAdapter extends MySqlAdapter {

    @Override
    public DbType getType() {
        return DbType.DORIS;
    }
}
