package com.dbgenius.model.metadata;

import lombok.Data;

import java.util.List;

/**
 * 统一的数据库索引元数据（中性模型，与具体数据库方言无关）。
 *
 * <p>对 MongoDB 而言对应集合上的索引，{@link #columns} 为索引键字段列表。</p>
 */
@Data
public class IndexMetadata {

    /** 索引名 */
    private String name;

    /** 索引覆盖的列（字段）名列表，按索引定义顺序排列 */
    private List<String> columns;
}
