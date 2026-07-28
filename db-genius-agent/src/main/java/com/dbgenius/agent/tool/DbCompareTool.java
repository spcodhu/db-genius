package com.dbgenius.agent.tool;

import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.model.metadata.ColumnMetadata;
import com.dbgenius.model.metadata.SchemaMetadata;
import com.dbgenius.model.metadata.TableMetadata;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.database.DatabaseAdapter;
import com.dbgenius.service.database.DatabaseAdapterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 数据库结构对比工具（适配器统一视图 + 中性模型 diff）。
 *
 * <p><b>设计说明：</b>本工具不再直连 JDBC 读取 {@code DatabaseMetaData}，而是让两侧各自的
 * {@link DatabaseAdapter} 把元数据抽取为与方言无关的中性模型 {@link SchemaMetadata}，
 * diff 逻辑只面向中性模型编程。由此带来两点收益：
 * ① 支持 MySQL/PostgreSQL/MongoDB 三种类型之间的任意两两对比——跨类型对比时
 * 类型名差异会自然体现为 MODIFY_COLUMN（如 MySQL 的 INT(11) vs PostgreSQL 的 int4(10)），
 * 由 LLM 结合方言差异解读是否等价；② 未来新增数据库类型时，只要其实现了
 * {@code extractSchema}，本工具零改动即可对比（开闭原则）。</p>
 *
 * <p>输出 JSON 的键结构与历史版本兼容：newTables / droppedTables / alteredTables，
 * change 类型枚举值（ADD_COLUMN / DROP_COLUMN / MODIFY_COLUMN / MODIFY_NULLABLE）不变。
 * 某一侧元数据抽取部分失败（{@link SchemaMetadata#getErrorMessage()} 非空）时不中断，
 * 在结果中附 {@code preError} / {@code testError} 提示字段。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbCompareTool {

    private final DbConfigService dbConfigService;
    private final DatabaseAdapterRegistry adapterRegistry;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    @Tool(description = "Compare the table structures of two databases (pre/production vs test). "
            + "Supports MySQL, PostgreSQL and MongoDB in any combination. "
            + "Returns a detailed diff report including new tables, dropped tables and column changes.")
    public String compareDatabases(
            @ToolParam(description = "The pre (production mirror) database config ID") Long preDbConfigId,
            @ToolParam(description = "The test database config ID") Long testDbConfigId) {
        log.info("Comparing databases: pre={}, test={}", preDbConfigId, testDbConfigId);

        try {
            DbConfig preConfig = dbConfigService.getById(preDbConfigId);
            DbConfig testConfig = dbConfigService.getById(testDbConfigId);
            if (preConfig == null || testConfig == null) {
                return "Error: One or both database configs not found.";
            }
            if (preConfig.getStatus() != DbConfigStatus.CONNECTED) {
                return "Error: Pre database config has not passed connectivity verification.";
            }
            if (testConfig.getStatus() != DbConfigStatus.CONNECTED) {
                return "Error: Test database config has not passed connectivity verification.";
            }

            // 两侧各自用自己的适配器抽取中性 Schema 元数据（支持四种类型任意两两对比）
            SchemaMetadata preSchema = extractSchema(preConfig);
            SchemaMetadata testSchema = extractSchema(testConfig);

            Map<String, Map<String, ColumnMetadata>> preTables = toTableColumnMap(preSchema);
            Map<String, Map<String, ColumnMetadata>> testTables = toTableColumnMap(testSchema);

            Map<String, Object> report = new LinkedHashMap<>();
            List<Map<String, Object>> newTables = new ArrayList<>();
            List<Map<String, Object>> droppedTables = new ArrayList<>();
            List<Map<String, Object>> alteredTables = new ArrayList<>();

            Set<String> allTableNames = new TreeSet<>();
            allTableNames.addAll(preTables.keySet());
            allTableNames.addAll(testTables.keySet());

            for (String tableName : allTableNames) {
                boolean inPre = preTables.containsKey(tableName);
                boolean inTest = testTables.containsKey(tableName);

                if (!inPre && inTest) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("table", tableName);
                    entry.put("columnCount", testTables.get(tableName).size());
                    newTables.add(entry);
                } else if (inPre && !inTest) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("table", tableName);
                    entry.put("columnCount", preTables.get(tableName).size());
                    droppedTables.add(entry);
                } else {
                    List<Map<String, Object>> changes =
                            compareColumns(preTables.get(tableName), testTables.get(tableName));
                    if (!changes.isEmpty()) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("table", tableName);
                        entry.put("changes", changes);
                        alteredTables.add(entry);
                    }
                }
            }

            report.put("success", true);
            report.put("preDatabase", preConfig.getDbName());
            report.put("testDatabase", testConfig.getDbName());
            report.put("summary", Map.of(
                    "newTables", newTables.size(),
                    "droppedTables", droppedTables.size(),
                    "alteredTables", alteredTables.size()
            ));
            report.put("newTables", newTables);
            report.put("droppedTables", droppedTables);
            report.put("alteredTables", alteredTables);
            // 某一侧元数据部分失败不中断，仅在报告中附提示字段，交由 LLM 向用户说明
            if (preSchema.getErrorMessage() != null && !preSchema.getErrorMessage().isBlank()) {
                report.put("preError", "Pre 库元数据读取不完整：" + preSchema.getErrorMessage());
            }
            if (testSchema.getErrorMessage() != null && !testSchema.getErrorMessage().isBlank()) {
                report.put("testError", "Test 库元数据读取不完整：" + testSchema.getErrorMessage());
            }

            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.error("Database comparison failed", e);
            return "Error: Comparison failed - " + e.getMessage();
        }
    }

    /**
     * 按配置的数据库类型取适配器并抽取中性 Schema 元数据。
     * 解密失败等异常向上抛，由外层统一降级为错误文本。
     */
    private SchemaMetadata extractSchema(DbConfig config) {
        DatabaseAdapter adapter = adapterRegistry.getAdapter(config.getDbType());
        String password = AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);
        return adapter.extractSchema(config, password);
    }

    /** 把中性模型转为「表名 → (列名 → 列元数据)」的索引，便于按名匹配 diff。 */
    private Map<String, Map<String, ColumnMetadata>> toTableColumnMap(SchemaMetadata schema) {
        Map<String, Map<String, ColumnMetadata>> tables = new LinkedHashMap<>();
        for (TableMetadata table : schema.getTables()) {
            Map<String, ColumnMetadata> columns = new LinkedHashMap<>();
            for (ColumnMetadata column : table.getColumns()) {
                columns.put(column.getName(), column);
            }
            tables.put(table.getName(), columns);
        }
        return tables;
    }

    /**
     * 列级 diff：列名匹配；类型字符串不等 → MODIFY_COLUMN（带 old/new 两侧类型）；
     * nullable 不等 → MODIFY_NULLABLE。
     *
     * <p>中性模型的类型字符串已含长度信息（如 "VARCHAR(255)"），跨类型对比时
     * 方言差异（如 INT(11) vs int4(10)）会自然体现为 MODIFY_COLUMN，由上层解读。</p>
     */
    private List<Map<String, Object>> compareColumns(Map<String, ColumnMetadata> preCols,
                                                     Map<String, ColumnMetadata> testCols) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> allColumns = new TreeSet<>();
        allColumns.addAll(preCols.keySet());
        allColumns.addAll(testCols.keySet());

        for (String colName : allColumns) {
            boolean inPre = preCols.containsKey(colName);
            boolean inTest = testCols.containsKey(colName);

            if (!inPre && inTest) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("change", "ADD_COLUMN");
                change.put("column", colName);
                change.put("type", testCols.get(colName).getType());
                changes.add(change);
            } else if (inPre && !inTest) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("change", "DROP_COLUMN");
                change.put("column", colName);
                change.put("type", preCols.get(colName).getType());
                changes.add(change);
            } else {
                ColumnMetadata pre = preCols.get(colName);
                ColumnMetadata test = testCols.get(colName);
                if (!Objects.equals(pre.getType(), test.getType())) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("change", "MODIFY_COLUMN");
                    change.put("column", colName);
                    change.put("preType", pre.getType());
                    change.put("testType", test.getType());
                    changes.add(change);
                }
                if (pre.isNullable() != test.isNullable()) {
                    changes.add(Map.of(
                            "change", "MODIFY_NULLABLE",
                            "column", colName,
                            "preNullable", pre.isNullable(),
                            "testNullable", test.isNullable()
                    ));
                }
            }
        }
        return changes;
    }
}
