package com.dbgenius.model.metadata;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的数据库表元数据（中性模型，与具体数据库方言无关）。
 *
 * <p>对 MongoDB 而言，一张「表」对应一个集合（Collection），
 * 列信息来自对集合中文档的采样推断。</p>
 */
@Data
public class TableMetadata {

    /** 表（集合）名 */
    private String name;

    /** 表注释，无注释时为 null */
    private String comment;

    /** 近似行数（文档数），统计失败时为 0 */
    private long rowCount;

    /** 列（字段）元数据列表 */
    private List<ColumnMetadata> columns = new ArrayList<>();

    /** 索引元数据列表 */
    private List<IndexMetadata> indexes = new ArrayList<>();
}
