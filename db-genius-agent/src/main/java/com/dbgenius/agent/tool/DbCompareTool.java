package com.dbgenius.agent.tool;

import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.service.DbConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbCompareTool {

    private final DbConfigService dbConfigService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    @Tool(description = "Compare the table structures of two databases (pre/production vs test). Returns a detailed diff report including new tables, dropped tables, column changes, and index changes.")
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

            Map<String, Map<String, ColumnInfo>> preTables = readTableStructures(preConfig);
            Map<String, Map<String, ColumnInfo>> testTables = readTableStructures(testConfig);

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
                    Map<String, ColumnInfo> preCols = preTables.get(tableName);
                    Map<String, ColumnInfo> testCols = testTables.get(tableName);
                    List<Map<String, Object>> changes = compareColumns(tableName, preCols, testCols);
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

            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.error("Database comparison failed", e);
            return "Error: Comparison failed - " + e.getMessage();
        }
    }

    private Map<String, Map<String, ColumnInfo>> readTableStructures(DbConfig config) throws SQLException {
        String url = String.format("jdbc:%s://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                config.getDbType(), config.getHost(), config.getPort(), config.getDbName());
        String password = AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);

        Map<String, Map<String, ColumnInfo>> tables = new LinkedHashMap<>();

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), password)) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet tableRs = metaData.getTables(config.getDbName(), null, "%", new String[]{"TABLE"});

            while (tableRs.next()) {
                String tableName = tableRs.getString("TABLE_NAME");
                Map<String, ColumnInfo> columns = new LinkedHashMap<>();

                ResultSet colRs = metaData.getColumns(config.getDbName(), null, tableName, "%");
                while (colRs.next()) {
                    ColumnInfo info = new ColumnInfo();
                    info.name = colRs.getString("COLUMN_NAME");
                    info.type = colRs.getString("TYPE_NAME");
                    info.size = colRs.getInt("COLUMN_SIZE");
                    info.nullable = colRs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                    info.defaultValue = colRs.getString("COLUMN_DEF");
                    columns.put(info.name, info);
                }
                colRs.close();
                tables.put(tableName, columns);
            }
            tableRs.close();
        }
        return tables;
    }

    private List<Map<String, Object>> compareColumns(String tableName,
                                                      Map<String, ColumnInfo> preCols,
                                                      Map<String, ColumnInfo> testCols) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> allColumns = new TreeSet<>();
        allColumns.addAll(preCols.keySet());
        allColumns.addAll(testCols.keySet());

        for (String colName : allColumns) {
            boolean inPre = preCols.containsKey(colName);
            boolean inTest = testCols.containsKey(colName);

            if (!inPre && inTest) {
                changes.add(Map.of(
                        "change", "ADD_COLUMN",
                        "column", colName,
                        "type", testCols.get(colName).type + "(" + testCols.get(colName).size + ")"
                ));
            } else if (inPre && !inTest) {
                changes.add(Map.of(
                        "change", "DROP_COLUMN",
                        "column", colName,
                        "type", preCols.get(colName).type + "(" + preCols.get(colName).size + ")"
                ));
            } else {
                ColumnInfo pre = preCols.get(colName);
                ColumnInfo test = testCols.get(colName);
                if (!pre.type.equals(test.type) || pre.size != test.size) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("change", "MODIFY_COLUMN");
                    change.put("column", colName);
                    change.put("preType", pre.type + "(" + pre.size + ")");
                    change.put("testType", test.type + "(" + test.size + ")");
                    changes.add(change);
                }
                if (pre.nullable != test.nullable) {
                    changes.add(Map.of(
                            "change", "MODIFY_NULLABLE",
                            "column", colName,
                            "preNullable", pre.nullable,
                            "testNullable", test.nullable
                    ));
                }
            }
        }
        return changes;
    }

    private static class ColumnInfo {
        String name;
        String type;
        int size;
        boolean nullable;
        String defaultValue;
    }
}
