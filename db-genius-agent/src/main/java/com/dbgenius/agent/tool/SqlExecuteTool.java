package com.dbgenius.agent.tool;

import com.dbgenius.common.util.AesUtil;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.model.enums.DbConfigStatus;
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
public class SqlExecuteTool {

    private final DbConfigService dbConfigService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    @Tool(description = "Execute a SQL statement on the specified database. Returns query results as JSON. Use this for SELECT, INSERT, UPDATE, DELETE, and DDL statements.")
    public String executeSql(
            @ToolParam(description = "The database configuration ID") Long dbConfigId,
            @ToolParam(description = "The SQL statement to execute") String sql) {
        log.info("Executing SQL on db {}: {}", dbConfigId, sql);

        DbConfig config;
        try {
            config = dbConfigService.getById(dbConfigId);
            if (config == null) {
                return "Error: Database config not found for ID " + dbConfigId;
            }
            if (config.getStatus() != DbConfigStatus.CONNECTED) {
                return "Error: Database config has not passed connectivity verification (status=" + config.getStatus().getDesc() + "). Please check the configuration.";
            }
        } catch (Exception e) {
            return "Error: Failed to get database config - " + e.getMessage();
        }

        String url = String.format("jdbc:%s://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                config.getDbType(), config.getHost(), config.getPort(), config.getDbName());
        String password = AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), password)) {
            boolean hasResultSet = sql.trim().toUpperCase().startsWith("SELECT")
                    || sql.trim().toUpperCase().startsWith("SHOW")
                    || sql.trim().toUpperCase().startsWith("DESC");

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (hasResultSet) {
                    try (ResultSet rs = stmt.executeQuery()) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        ResultSetMetaData metaData = rs.getMetaData();
                        int colCount = metaData.getColumnCount();

                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= colCount; i++) {
                                row.put(metaData.getColumnLabel(i), rs.getObject(i));
                            }
                            rows.add(row);
                            if (rows.size() >= 100) break;
                        }

                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("success", true);
                        result.put("rowCount", rows.size());
                        result.put("data", rows);
                        return objectMapper.writeValueAsString(result);
                    }
                } else {
                    int affected = stmt.executeUpdate();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("success", true);
                    result.put("affectedRows", affected);
                    result.put("message", "SQL executed successfully. " + affected + " row(s) affected.");
                    return objectMapper.writeValueAsString(result);
                }
            }
        } catch (SQLException e) {
            log.error("SQL execution failed: {}", e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("sqlState", e.getSQLState());
            result.put("errorCode", e.getErrorCode());
            try {
                return objectMapper.writeValueAsString(result);
            } catch (Exception ex) {
                return "Error: " + e.getMessage();
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
