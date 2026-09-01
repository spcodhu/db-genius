package com.dbgenius.agent.tool;

import com.dbgenius.agent.metrics.AgentMetrics;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.common.util.SqlSafetyGuard;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.database.AbstractJdbcAdapter;
import com.dbgenius.service.database.DatabaseAdapter;
import com.dbgenius.service.database.DatabaseAdapterRegistry;
import com.dbgenius.service.database.MongoDbAdapter;
import com.dbgenius.trial.TrialGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * SQL / 数据库命令执行工具（策略分派 + 安全红线双层防护）。
 *
 * <p><b>设计说明：</b>本工具是 LLM 执行数据库语句的唯一入口，对 LLM 的契约
 * （{@code executeSql} 方法名与参数）保持稳定。内部不再直连 JDBC，而是：
 * ① 通过 {@link DatabaseAdapterRegistry} 按配置的数据库类型获取
 * {@link DatabaseAdapter} 策略；② 执行前先经 {@link SqlSafetyGuard} 硬性拦截
 * DROP / TRUNCATE / drop / dropDatabase 等破坏性命令——这是系统级安全红线，
 * <b>即使用户明确要求也不放行</b>，与各 Agent 系统提示词中的安全条款共同构成
 * 「提示词约束 + 代码强制」的双层防护；③ 按适配器类型分派执行：
 * JDBC 系（MySQL/PostgreSQL）走 {@link AbstractJdbcAdapter#openConnection}，
 * MongoDB 委托 {@link MongoDbAdapter#executeCommand} 执行 JSON 命令。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SqlExecuteTool {

    /** 查询结果行数上限，防止大结果集撑爆上下文 */
    private static final int MAX_ROWS = 100;

    private final DbConfigService dbConfigService;
    private final TrialGuard trialGuard;
    private final DatabaseAdapterRegistry adapterRegistry;
    private final AgentMetrics agentMetrics;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    /**
     * 单条语句的执行超时（秒）：防止一条慢 SQL 把整个分钟级的 Agent 轮次拖死。
     * 超时按统一失败格式返回给 LLM，附带可行动的收窄建议。
     */
    @Value("${db-genius.tool.sql-timeout-seconds:30}")
    private int sqlTimeoutSeconds;

    @Tool(description = "Execute a statement on the specified database and return results as JSON. "
            + "For SQL-based databases (MySQL/PostgreSQL/MariaDB/TiDB/Doris/StarRocks/OceanBase/Oracle/SQL Server), "
            + "pass a standard SQL statement matching the target dialect (SELECT/INSERT/UPDATE/DELETE/DDL). "
            + "For MongoDB, pass a JSON command: {\"collection\":\"c\",\"operation\":\"find|count|distinct|aggregate\","
            + "\"filter\":{...},\"field\":\"x\",\"pipeline\":[...],\"limit\":100}. "
            + "Destructive commands (DROP, TRUNCATE, MongoDB drop/dropDatabase) are hard-rejected by the system "
            + "and can never be executed, even if the user explicitly asks.")
    public String executeSql(
            @ToolParam(description = "The database configuration ID") Long dbConfigId,
            @ToolParam(description = "The SQL statement to execute, or a JSON command for MongoDB") String sql) {
        log.info("Executing statement on db {}: {}", dbConfigId, sql);

        // 1. 取配置并做连通性状态检查
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

        // 2. 按数据库类型获取适配器策略
        DatabaseAdapter adapter;
        try {
            adapter = adapterRegistry.getAdapter(config.getDbType());
        } catch (BusinessException e) {
            return failureJson(e.getMessage());
        }

        // 3. 安全红线第一道：破坏性命令硬性拦截。
        //    即使用户明确要求也不放行——提示词层与代码层双重防护，此处为代码层兜底。
        try {
            if (adapter instanceof MongoDbAdapter) {
                SqlSafetyGuard.assertMongoCommandSafe(sql);
            } else {
                SqlSafetyGuard.assertSafe(sql);
            }
        } catch (BusinessException e) {
            // 安全红线拦截计数：衡量「护栏在工作」，要告警的是趋势突增而非绝对值
            agentMetrics.recordSqlBlocked(config.getDbType());
            log.warn("安全红线拦截破坏性命令（db {}）：{}", dbConfigId, sql);
            return failureJson(e.getMessage());
        }

        // 4. 试用版只读限制：只读判断委托给方言感知的适配器
        if (trialGuard.isTrialMode() && !adapter.isReadOnlyStatement(sql)) {
            log.warn("Trial mode rejected non-readonly statement: {}", sql);
            return failureJson("试用版仅支持只读查询（SELECT/SHOW/DESC）");
        }

        // 5. 解密密码（沿用 AES-256-GCM），失败按工具失败格式返回
        String password;
        try {
            password = AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);
        } catch (Exception e) {
            log.error("解密数据库密码失败（db {}）：{}", dbConfigId, e.getMessage());
            return failureJson("Failed to decrypt database password");
        }

        // 6. 按适配器类型分派执行
        if (adapter instanceof AbstractJdbcAdapter jdbc) {
            return executeJdbc(jdbc, adapter, config, password, sql);
        }
        if (adapter instanceof MongoDbAdapter mongo) {
            return executeMongo(mongo, config, password, sql);
        }
        return failureJson("Unsupported adapter type: " + adapter.getClass().getSimpleName());
    }

    /**
     * JDBC 系（MySQL/PostgreSQL）执行路径：查询限 {@link #MAX_ROWS} 行，
     * 结果保持既有 JSON 结构（success/rowCount/data 或 success/affectedRows/message）。
     */
    private String executeJdbc(AbstractJdbcAdapter jdbc, DatabaseAdapter adapter,
                               DbConfig config, String password, String sql) {
        try (Connection conn = jdbc.openConnection(config, password)) {
            boolean hasResultSet = adapter.isReadOnlyStatement(sql);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (sqlTimeoutSeconds > 0) {
                    stmt.setQueryTimeout(sqlTimeoutSeconds);
                }
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
                            if (rows.size() >= MAX_ROWS) break;
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
        } catch (SQLTimeoutException e) {
            log.warn("SQL execution timed out after {}s: {}", sqlTimeoutSeconds, sql);
            return failureJson("Statement timed out after " + sqlTimeoutSeconds + " seconds and was cancelled. "
                    + "Do NOT re-run it unchanged - narrow the scope first: add WHERE/LIMIT, "
                    + "select fewer columns, or use aggregates (COUNT/SUM/GROUP BY).");
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

    /**
     * MongoDB 执行路径：委托 {@link MongoDbAdapter#executeCommand} 执行 JSON 命令，
     * 把返回的 JSON（find/aggregate 为数组、count 为数字、distinct 为 {"values":[...]}）
     * 原样嵌入工具结果结构的 {@code result} 字段。
     */
    private String executeMongo(MongoDbAdapter mongo, DbConfig config, String password, String commandJson) {
        try {
            String commandResult = mongo.executeCommand(config, password, commandJson);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            // readTree 把命令结果解析为 JSON 节点嵌入，避免二次转义成字符串
            result.put("result", objectMapper.readTree(commandResult));
            return objectMapper.writeValueAsString(result);
        } catch (BusinessException e) {
            // 命令非法（400）或含写操作（403）等业务异常，按失败格式返回给 LLM
            log.warn("MongoDB 命令被拒绝（db {}）：{}", config.getId(), e.getMessage());
            return failureJson(e.getMessage());
        } catch (Exception e) {
            log.error("MongoDB command execution failed: {}", e.getMessage());
            return failureJson("MongoDB command failed - " + e.getMessage());
        }
    }

    /** 工具统一失败格式：success=false + 错误消息，绝不把异常抛穿 agent 循环。 */
    private String failureJson(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return "Error: " + message;
        }
    }
}
