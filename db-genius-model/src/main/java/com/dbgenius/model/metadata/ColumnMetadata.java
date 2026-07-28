package com.dbgenius.model.metadata;

import lombok.Data;

/**
 * 统一的数据库列元数据（适配器模式的「统一视图」产物之一）。
 *
 * <p><b>设计说明：</b>不同数据库（MySQL/PostgreSQL/MongoDB，乃至未来的向量库）
 * 的元数据形态差异很大，本类将其收敛为与方言无关的中性模型，
 * 供文档渲染器（{@code DatabaseDocRenderer}）和库间结构对比（{@code DbCompareTool}）共用，
 * 使上层逻辑完全面向抽象编程，不感知具体数据库方言。</p>
 *
 * <p>对 MongoDB 而言，一列对应集合中文档的一个字段，{@link #type} 为 BSON 类型名。</p>
 */
@Data
public class ColumnMetadata {

    /** 列（字段）名 */
    private String name;

    /** 类型描述，已带长度信息，如 "VARCHAR(255)"、"INT(11)"、MongoDB 的 "string" */
    private String type;

    /** 是否允许为 NULL（MongoDB 字段恒为 true） */
    private boolean nullable;

    /** 是否主键列 */
    private boolean primaryKey;

    /** 列注释，无注释时为 null */
    private String comment;
}
