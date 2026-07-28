package com.dbgenius.service.database;

import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.metadata.ColumnMetadata;
import com.dbgenius.model.metadata.IndexMetadata;
import com.dbgenius.model.metadata.SchemaMetadata;
import com.dbgenius.model.metadata.TableMetadata;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC 系数据库适配器抽象基类（模板方法模式 Template Method）。
 *
 * <p><b>设计说明：</b>MySQL / PostgreSQL 都通过 JDBC 访问，
 * 「打开连接 → 遍历表 → 抽取列/主键/索引 → 组装中性元数据」这一主流程完全一致，
 * 只有 URL 模板、catalog/schema 定位、标识符引号等方言细节不同。
 * 本类把公共流程固化为模板（{@link #testConnection}、{@link #extractSchema}），
 * 方言差异下沉为钩子方法（{@link #buildJdbcUrl}、{@link #catalog}、
 * {@link #schemaPattern}、{@link #quoteIdentifier}）。</p>
 *
 * <p><b>扩展方式：</b>新增 JDBC 系数据库（如 Oracle）时，继承本类并：
 * ① 实现 {@link #getType()}；② 实现 {@link #buildJdbcUrl} 与 {@link #quoteIdentifier}
 * （强制显式声明引号，避免默认引号在某种方言下静默出错）；③ 按需覆盖
 * {@link #catalog}/{@link #schemaPattern}/{@link #isReadOnlyStatement}；
 * ④ 标注 {@code @Component} 即可被 {@link DatabaseAdapterRegistry} 自动收集。</p>
 */
@Slf4j
public abstract class AbstractJdbcAdapter implements DatabaseAdapter {

    /**
     * 打开到目标库的 JDBC 连接。
     *
     * <p>本方法是 agent 模块 SqlExecuteTool 执行 SQL 的统一连接入口，走账密认证。</p>
     *
     * @param config            数据库配置
     * @param decryptedPassword 已解密的明文密码
     * @return JDBC 连接，调用方负责关闭
     * @throws SQLException 连接失败时抛出（由调用方决定如何降级）
     */
    public Connection openConnection(DbConfig config, String decryptedPassword) throws SQLException {
        return DriverManager.getConnection(buildJdbcUrl(config), config.getUsername(), decryptedPassword);
    }

    /**
     * 模板方法：测试连通性。任何异常都只记日志并返回 false，绝不向外抛。
     */
    @Override
    public boolean testConnection(DbConfig config, String decryptedPassword) {
        try (Connection conn = openConnection(config, decryptedPassword)) {
            return conn.isValid(5);
        } catch (Exception e) {
            log.warn("测试 {} 连接失败（{}:{} / {}）：{}", getType().getCode(),
                    config.getHost(), config.getPort(), config.getDbName(), e.getMessage());
            return false;
        }
    }

    /**
     * 模板方法：抽取 Schema 元数据。
     *
     * <p>部分失败不中断：表级异常（如行数统计失败）降级为默认值继续；
     * 连接/元数据级 SQLException 写入 {@link SchemaMetadata#errorMessage}，
     * 返回已抽取的部分结果。</p>
     */
    @Override
    public SchemaMetadata extractSchema(DbConfig config, String decryptedPassword) {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType(getType().getCode());
        schema.setDbTypeDisplayName(getType().getDisplayName());
        schema.setDatabaseName(config.getDbName());
        schema.setHost(config.getHost());
        schema.setPort(config.getPort());

        try (Connection conn = openConnection(config, decryptedPassword)) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet tables = metaData.getTables(
                    catalog(config), schemaPattern(config), "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    TableMetadata table = new TableMetadata();
                    table.setName(tableName);
                    // REMARKS 为空串时按无注释处理，保持与线上文档格式一致
                    String remarks = tables.getString("REMARKS");
                    table.setComment(remarks != null && !remarks.isBlank() ? remarks : null);
                    table.setRowCount(countRows(conn, tableName));
                    table.setColumns(extractColumns(metaData, config, tableName));
                    table.setIndexes(extractIndexes(metaData, config, tableName));
                    schema.getTables().add(table);
                }
            }
        } catch (SQLException e) {
            // 不抛出：保留已抽取的部分结果，错误信息写入 errorMessage 供上层展示
            log.error("读取 {} 数据库元数据失败（{}）：{}", getType().getCode(), config.getDbName(), e.getMessage(), e);
            schema.setErrorMessage(e.getMessage());
        }
        return schema;
    }

    /**
     * 统计单表近似行数；失败记 0 并降级（不影响其他表的抽取）。
     */
    private long countRows(Connection conn, String tableName) {
        String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(tableName);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.debug("统计表 {} 行数失败：{}", tableName, e.getMessage());
        }
        return 0;
    }

    /**
     * 抽取列元数据：列名、类型（TYPE_NAME(COLUMN_SIZE)）、可空、主键、注释。
     */
    private List<ColumnMetadata> extractColumns(DatabaseMetaData metaData, DbConfig config, String tableName)
            throws SQLException {
        // 先取主键列集合，再逐列组装
        Set<String> pkColumns = new HashSet<>();
        try (ResultSet primaryKeys = metaData.getPrimaryKeys(catalog(config), schemaPattern(config), tableName)) {
            while (primaryKeys.next()) {
                pkColumns.add(primaryKeys.getString("COLUMN_NAME"));
            }
        }

        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet rs = metaData.getColumns(catalog(config), schemaPattern(config), tableName, "%")) {
            while (rs.next()) {
                ColumnMetadata column = new ColumnMetadata();
                String colName = rs.getString("COLUMN_NAME");
                column.setName(colName);
                // 类型统一拼为 TYPE_NAME(COLUMN_SIZE)，如 VARCHAR(255)，与线上文档格式一致
                column.setType(rs.getString("TYPE_NAME") + "(" + rs.getInt("COLUMN_SIZE") + ")");
                column.setNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                column.setPrimaryKey(pkColumns.contains(colName));
                column.setComment(rs.getString("REMARKS"));
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * 抽取索引元数据：按索引名聚合列列表，跳过 INDEX_NAME/COLUMN_NAME 为 null 的行
     * （部分驱动会返回统计信息行，两者为 null）。
     */
    private List<IndexMetadata> extractIndexes(DatabaseMetaData metaData, DbConfig config, String tableName)
            throws SQLException {
        Map<String, List<String>> indexMap = new LinkedHashMap<>();
        try (ResultSet rs = metaData.getIndexInfo(catalog(config), schemaPattern(config), tableName, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String colName = rs.getString("COLUMN_NAME");
                if (indexName != null && colName != null) {
                    indexMap.computeIfAbsent(indexName, k -> new ArrayList<>()).add(colName);
                }
            }
        }
        List<IndexMetadata> indexes = new ArrayList<>();
        indexMap.forEach((name, cols) -> {
            IndexMetadata index = new IndexMetadata();
            index.setName(name);
            index.setColumns(cols);
            indexes.add(index);
        });
        return indexes;
    }

    /**
     * 默认只读判断：SELECT/SHOW/DESC/EXPLAIN 前缀视为只读。子类可按方言覆盖。
     */
    @Override
    public boolean isReadOnlyStatement(String statement) {
        if (statement == null || statement.isBlank()) {
            return false;
        }
        String normalized = statement.trim().toUpperCase();
        return normalized.startsWith("SELECT")
                || normalized.startsWith("SHOW")
                || normalized.startsWith("DESC")
                || normalized.startsWith("EXPLAIN");
    }

    /**
     * 校验必填项工具：为空抛 BusinessException(400)，供各子类 validateRequest 复用。
     *
     * @param value     待校验值（String 判空白，其他类型判 null）
     * @param fieldName 字段名（用于错误信息）
     */
    protected void requireNonBlank(Object value, String fieldName) {
        boolean empty = value == null || (value instanceof String s && s.isBlank());
        if (empty) {
            throw new BusinessException(400, fieldName + " is required for " + getType().getDisplayName());
        }
    }

    /** 钩子（抽象）：按方言拼接 JDBC URL。 */
    protected abstract String buildJdbcUrl(DbConfig config);

    /** 钩子：JDBC catalog 定位参数，默认取 dbName（MySQL 场景）。 */
    protected String catalog(DbConfig config) {
        return config.getDbName();
    }

    /** 钩子：JDBC schemaPattern 定位参数，默认 null（MySQL 无 schema 概念）。 */
    protected String schemaPattern(DbConfig config) {
        return null;
    }

    /**
     * 钩子（抽象）：标识符引号。强制各方言显式声明
     * （MySQL 反引号，PostgreSQL 双引号），避免默认引号在新方言下静默出错。
     */
    protected abstract String quoteIdentifier(String identifier);
}
