package com.dbgenius.service.database;

import com.dbgenius.model.metadata.ColumnMetadata;
import com.dbgenius.model.metadata.IndexMetadata;
import com.dbgenius.model.metadata.SchemaMetadata;
import com.dbgenius.model.metadata.TableMetadata;

/**
 * 数据库 Schema 文档渲染器（静态工具类）。
 *
 * <p><b>兼容要求：</b>输出格式与历史线上版本逐字符保持一致——
 * 该 Markdown 文档会被原样注入 LLM prompt，任何格式漂移都可能影响生成效果。
 * 本类只负责把中性元数据 {@link SchemaMetadata} 渲染为文档，
 * 不感知任何数据库方言。</p>
 */
public final class DatabaseDocRenderer {

    private DatabaseDocRenderer() {
        // 工具类禁止实例化
    }

    /**
     * 把 Schema 元数据渲染为 Markdown 文档。
     *
     * @param schema 中性 Schema 元数据
     * @return Markdown 文档文本
     */
    public static String render(SchemaMetadata schema) {
        StringBuilder doc = new StringBuilder();
        doc.append("# Database: ").append(schema.getDatabaseName()).append("\n\n");
        doc.append("- Type: ").append(schema.getDbType()).append("\n");
        if (schema.getHost() != null) {
            // host 为 null 时不输出 Host 行
            doc.append("- Host: ").append(schema.getHost()).append(":").append(schema.getPort()).append("\n\n");
        } else {
            doc.append("\n");
        }

        for (TableMetadata table : schema.getTables()) {
            doc.append("## Table: ").append(table.getName()).append("\n");
            if (table.getComment() != null && !table.getComment().isBlank()) {
                doc.append("Comment: ").append(table.getComment()).append("\n");
            }
            doc.append("Row count: ~").append(table.getRowCount()).append("\n\n");

            doc.append("| Column | Type | Nullable | Key | Comment |\n");
            doc.append("|--------|------|----------|-----|----------|\n");
            for (ColumnMetadata column : table.getColumns()) {
                doc.append("| ").append(column.getName())
                        .append(" | ").append(column.getType())
                        .append(" | ").append(column.isNullable() ? "YES" : "NO")
                        .append(" | ").append(column.isPrimaryKey() ? "PK" : "")
                        .append(" | ").append(column.getComment() != null ? column.getComment() : "")
                        .append(" |\n");
            }

            if (!table.getIndexes().isEmpty()) {
                doc.append("\n**Indexes:**\n");
                for (IndexMetadata index : table.getIndexes()) {
                    doc.append("- ").append(index.getName())
                            .append(": ").append(String.join(", ", index.getColumns())).append("\n");
                }
            }
            doc.append("\n---\n\n");
        }

        if (schema.getErrorMessage() != null && !schema.getErrorMessage().isBlank()) {
            // 部分失败时把错误附在文档末尾，供 LLM 感知数据不完整
            doc.append("\n**Error reading metadata:** ").append(schema.getErrorMessage());
        }
        return doc.toString();
    }
}
