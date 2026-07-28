package com.dbgenius.model.metadata;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的数据库 Schema 元数据（适配器模式的「统一视图」根对象）。
 *
 * <p><b>设计说明：</b>每种数据库的 {@code DatabaseAdapter} 实现负责把各自的
 * 元数据（JDBC {@code DatabaseMetaData} / MongoDB 集合采样）抽取为本模型，
 * 之后文档生成与库间对比只依赖本模型，彻底与数据库方言解耦。
 * 这也是跨类型对比（如 MySQL vs PostgreSQL）能够实现的关键。</p>
 */
@Data
public class SchemaMetadata {

    /** 数据库类型编码（{@code DbType#getCode()}） */
    private String dbType;

    /** 数据库展示名称（{@code DbType#getDisplayName()}） */
    private String dbTypeDisplayName;

    /** 数据库名 */
    private String databaseName;

    /** 主机地址 */
    private String host;

    /** 端口，无端口概念时为 null */
    private Integer port;

    /** 表（集合）元数据列表 */
    private List<TableMetadata> tables = new ArrayList<>();

    /** 元数据读取过程中的错误信息（部分失败时记录，不中断整体生成） */
    private String errorMessage;
}
