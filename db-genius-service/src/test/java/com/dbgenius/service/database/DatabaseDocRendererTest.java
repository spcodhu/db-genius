package com.dbgenius.service.database;

import com.dbgenius.model.metadata.ColumnMetadata;
import com.dbgenius.model.metadata.IndexMetadata;
import com.dbgenius.model.metadata.SchemaMetadata;
import com.dbgenius.model.metadata.TableMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DatabaseDocRenderer 单元测试：断言 Markdown 输出严格符合线上格式
 * （该文档会被注入 LLM prompt，格式即契约）。
 */
class DatabaseDocRendererTest {

    /** 构造一个含表注释、PK 列、索引、错误信息的完整元数据 */
    private SchemaMetadata fullSchema() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setDbTypeDisplayName("MySQL");
        schema.setDatabaseName("testdb");
        schema.setHost("localhost");
        schema.setPort(3306);

        TableMetadata table = new TableMetadata();
        table.setName("users");
        table.setComment("用户表");
        table.setRowCount(1000);

        ColumnMetadata id = new ColumnMetadata();
        id.setName("id");
        id.setType("INT(11)");
        id.setNullable(false);
        id.setPrimaryKey(true);
        id.setComment("主键");

        ColumnMetadata name = new ColumnMetadata();
        name.setName("name");
        name.setType("VARCHAR(255)");
        name.setNullable(true);
        name.setPrimaryKey(false);
        name.setComment(null);

        table.setColumns(List.of(id, name));

        IndexMetadata index = new IndexMetadata();
        index.setName("idx_name");
        index.setColumns(List.of("name", "id"));
        table.setIndexes(List.of(index));

        schema.setTables(List.of(table));
        schema.setErrorMessage("部分表读取失败");
        return schema;
    }

    @Test
    void 输出包含头部与表格关键行() {
        String doc = DatabaseDocRenderer.render(fullSchema());
        assertTrue(doc.contains("# Database: testdb\n"));
        assertTrue(doc.contains("- Type: mysql\n"));
        assertTrue(doc.contains("- Host: localhost:3306\n"));
        assertTrue(doc.contains("## Table: users\n"));
        assertTrue(doc.contains("Comment: 用户表\n"));
        assertTrue(doc.contains("Row count: ~1000\n"));
        assertTrue(doc.contains("| Column | Type | Nullable | Key | Comment |\n"));
        assertTrue(doc.contains("|--------|------|----------|-----|----------|\n"));
        // PK 列 Key 列显示 PK；可空列显示 YES
        assertTrue(doc.contains("| id | INT(11) | NO | PK | 主键 |\n"));
        assertTrue(doc.contains("| name | VARCHAR(255) | YES |  |  |\n"));
        assertTrue(doc.contains("\n---\n\n"));
    }

    @Test
    void 索引段与错误行正确输出() {
        String doc = DatabaseDocRenderer.render(fullSchema());
        assertTrue(doc.contains("\n**Indexes:**\n"));
        assertTrue(doc.contains("- idx_name: name, id\n"));
        assertTrue(doc.contains("\n**Error reading metadata:** 部分表读取失败"));
    }

    @Test
    void host为null时不输出Host行() {
        SchemaMetadata schema = fullSchema();
        schema.setHost(null);
        schema.setPort(null);
        String doc = DatabaseDocRenderer.render(schema);
        assertFalse(doc.contains("- Host:"));
        // 其余头部仍正常
        assertTrue(doc.contains("# Database: testdb\n"));
        assertTrue(doc.contains("- Type: mysql\n"));
    }

    @Test
    void 无注释无索引无错误时对应段落不出现() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("sqlite");
        schema.setDatabaseName("/data/app.db");
        schema.setHost(null);

        TableMetadata table = new TableMetadata();
        table.setName("t1");
        table.setRowCount(0);
        schema.setTables(List.of(table));

        String doc = DatabaseDocRenderer.render(schema);
        assertFalse(doc.contains("Comment:"));
        assertFalse(doc.contains("**Indexes:**"));
        assertFalse(doc.contains("**Error reading metadata:**"));
        assertFalse(doc.contains("- Host:"));
        assertTrue(doc.contains("Row count: ~0\n"));
    }
}
