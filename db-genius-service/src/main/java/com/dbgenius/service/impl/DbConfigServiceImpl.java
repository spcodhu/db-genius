package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.mapper.DbConfigMapper;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.model.vo.DbConfigVO;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.trial.TrialGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class DbConfigServiceImpl extends ServiceImpl<DbConfigMapper, DbConfig> implements DbConfigService {

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    @org.springframework.beans.factory.annotation.Autowired
    private TrialGuard trialGuard;

    @Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private DbConfigService self;

    @Override
    public DbConfigVO createConfig(Long userId, DbConfigRequest request) {
        trialGuard.denyIfTrial("试用版暂不支持新增数据库配置");
        DbConfig config = new DbConfig();
        config.setUserId(userId);
        config.setName(request.getName());
        config.setDbType(request.getDbType() != null ? request.getDbType() : "mysql");
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDbName(request.getDbName());
        config.setUsername(request.getUsername());
        config.setPasswordEncrypted(AesUtil.encrypt(request.getPassword(), encryptKey));
        config.setStatus(DbConfigStatus.VERIFYING);
        save(config);
        self.autoVerifyAndGenerateDoc(config.getId());
        return toVO(config);
    }

    @Override
    public DbConfigVO updateConfig(Long userId, Long configId, DbConfigRequest request) {
        DbConfig config = getConfigEntity(userId, configId);
        trialGuard.denyIfTrialBuiltin(config);
        config.setName(request.getName());
        config.setDbType(request.getDbType() != null ? request.getDbType() : "mysql");
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDbName(request.getDbName());
        config.setUsername(request.getUsername());
        config.setPasswordEncrypted(AesUtil.encrypt(request.getPassword(), encryptKey));
        config.setStatus(DbConfigStatus.VERIFYING);
        config.setDocContent(null);
        config.setDocGeneratedAt(null);
        updateById(config);
        self.autoVerifyAndGenerateDoc(config.getId());
        return toVO(config);
    }

    @Override
    public void deleteConfig(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        trialGuard.denyIfTrialBuiltin(config);
        removeById(config.getId());
    }

    @Override
    public List<DbConfigVO> listConfigs(Long userId) {
        return list(new LambdaQueryWrapper<DbConfig>()
                .eq(DbConfig::getUserId, userId)
                .orderByDesc(DbConfig::getCreatedAt))
                .stream().map(this::toVO).toList();
    }

    @Override
    public DbConfigVO getConfig(Long userId, Long configId) {
        return toVO(getConfigEntity(userId, configId));
    }

    @Override
    public boolean testConnection(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        trialGuard.denyIfTrialBuiltin(config);
        boolean connected = tryConnect(config);
        config.setStatus(connected ? DbConfigStatus.CONNECTED : DbConfigStatus.FAILED);
        updateById(config);
        return connected;
    }

    @Override
    public String generateDoc(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        trialGuard.denyIfTrialBuiltin(config);
        String doc = buildDatabaseDoc(config);
        config.setDocContent(doc);
        config.setDocGeneratedAt(LocalDateTime.now());
        updateById(config);
        return doc;
    }

    @Override
    public String getDocContent(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        if (config.getDocContent() == null || config.getDocContent().isBlank()) {
            throw new BusinessException("Documentation not generated yet. Please generate it first.");
        }
        return config.getDocContent();
    }

    @Override
    public boolean isBuiltinConfig(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        return trialGuard.isBuiltin(config);
    }

    public String getDecryptedPassword(DbConfig config) {
        return AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);
    }

    @Async("dbGeniusExecutor")
    @Override
    public void autoVerifyAndGenerateDoc(Long configId) {
        log.info("Auto-verifying database config {}", configId);
        try {
            DbConfig config = getById(configId);
            if (config == null) {
                log.warn("Config {} not found for auto-verify", configId);
                return;
            }

            boolean connected = tryConnect(config);
            config.setStatus(connected ? DbConfigStatus.CONNECTED : DbConfigStatus.FAILED);

            if (connected) {
                log.info("Config {} connected, generating documentation", configId);
                String doc = buildDatabaseDoc(config);
                config.setDocContent(doc);
                config.setDocGeneratedAt(LocalDateTime.now());
            } else {
                log.warn("Config {} connection failed", configId);
            }

            updateById(config);
            log.info("Auto-verify complete for config {}, status={}", configId, config.getStatus());
        } catch (Exception e) {
            log.error("Auto-verify failed for config {}", configId, e);
            try {
                DbConfig config = getById(configId);
                if (config != null) {
                    config.setStatus(DbConfigStatus.FAILED);
                    updateById(config);
                }
            } catch (Exception ex) {
                log.error("Failed to update status after error", ex);
            }
        }
    }

    @Override
    public void validateConfigForChat(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        if (config.getStatus() != DbConfigStatus.CONNECTED) {
            String msg = switch (config.getStatus()) {
                case VERIFYING -> "数据库配置正在验证中，请稍后重试";
                case FAILED -> "数据库配置连接失败，请检查配置后重试";
                case CONNECTED -> throw new IllegalStateException("unreachable");
            };
            throw new BusinessException(400, msg);
        }
    }

    public DbConfig getConfigEntity(Long userId, Long configId) {
        DbConfig config = getById(configId);
        if (config == null || !config.getUserId().equals(userId)) {
            throw new BusinessException(404, "Database config not found");
        }
        return config;
    }

    private boolean tryConnect(DbConfig config) {
        String url = buildJdbcUrl(config);
        String password = AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);
        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), password)) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.warn("Connection test failed for config {}: {}", config.getId(), e.getMessage());
            return false;
        }
    }

    private String buildDatabaseDoc(DbConfig config) {
        String url = buildJdbcUrl(config);
        String password = AesUtil.decrypt(config.getPasswordEncrypted(), encryptKey);
        StringBuilder doc = new StringBuilder();
        doc.append("# Database: ").append(config.getDbName()).append("\n\n");
        doc.append("- Type: ").append(config.getDbType()).append("\n");
        doc.append("- Host: ").append(config.getHost()).append(":").append(config.getPort()).append("\n\n");

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), password)) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet tables = metaData.getTables(config.getDbName(), null, "%", new String[]{"TABLE"});

            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                String tableComment = tables.getString("REMARKS");
                doc.append("## Table: ").append(tableName).append("\n");
                if (tableComment != null && !tableComment.isBlank()) {
                    doc.append("Comment: ").append(tableComment).append("\n");
                }

                long rowCount = getRowCount(conn, tableName);
                doc.append("Row count: ~").append(rowCount).append("\n\n");

                doc.append("| Column | Type | Nullable | Key | Comment |\n");
                doc.append("|--------|------|----------|-----|----------|\n");

                ResultSet columns = metaData.getColumns(config.getDbName(), null, tableName, "%");
                ResultSet primaryKeys = metaData.getPrimaryKeys(config.getDbName(), null, tableName);
                Set<String> pkColumns = new HashSet<>();
                while (primaryKeys.next()) {
                    pkColumns.add(primaryKeys.getString("COLUMN_NAME"));
                }

                while (columns.next()) {
                    String colName = columns.getString("COLUMN_NAME");
                    String colType = columns.getString("TYPE_NAME");
                    int colSize = columns.getInt("COLUMN_SIZE");
                    String nullable = columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable ? "YES" : "NO";
                    String isKey = pkColumns.contains(colName) ? "PK" : "";
                    String comment = columns.getString("REMARKS");

                    doc.append("| ").append(colName)
                            .append(" | ").append(colType).append("(").append(colSize).append(")")
                            .append(" | ").append(nullable)
                            .append(" | ").append(isKey)
                            .append(" | ").append(comment != null ? comment : "")
                            .append(" |\n");
                }
                columns.close();

                ResultSet indexes = metaData.getIndexInfo(config.getDbName(), null, tableName, false, false);
                Map<String, List<String>> indexMap = new LinkedHashMap<>();
                while (indexes.next()) {
                    String indexName = indexes.getString("INDEX_NAME");
                    String colName = indexes.getString("COLUMN_NAME");
                    if (indexName != null && colName != null) {
                        indexMap.computeIfAbsent(indexName, k -> new ArrayList<>()).add(colName);
                    }
                }
                indexes.close();

                if (!indexMap.isEmpty()) {
                    doc.append("\n**Indexes:**\n");
                    indexMap.forEach((name, cols) ->
                            doc.append("- ").append(name).append(": ").append(String.join(", ", cols)).append("\n"));
                }
                doc.append("\n---\n\n");
            }
            tables.close();
        } catch (SQLException e) {
            log.error("Failed to read database metadata", e);
            doc.append("\n**Error reading metadata:** ").append(e.getMessage());
        }
        return doc.toString();
    }

    private long getRowCount(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM `" + tableName + "`")) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) {
            log.debug("Failed to count rows for table {}", tableName);
        }
        return 0;
    }

    private String buildJdbcUrl(DbConfig config) {
        return String.format("jdbc:%s://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                config.getDbType(), config.getHost(), config.getPort(), config.getDbName());
    }

    private DbConfigVO toVO(DbConfig config) {
        DbConfigVO vo = new DbConfigVO();
        vo.setId(config.getId());
        vo.setName(config.getName());

        boolean mask = trialGuard.isTrialMode() && trialGuard.isBuiltin(config);
        if (mask) {
            vo.setDbType("*");
            vo.setHost("*");
            vo.setPort(0);
            vo.setDbName("*");
            vo.setUsername("*");
            vo.setDocContent("*");
        } else {
            vo.setDbType(config.getDbType());
            vo.setHost(config.getHost());
            vo.setPort(config.getPort());
            vo.setDbName(config.getDbName());
            vo.setUsername(config.getUsername());
            vo.setDocContent(config.getDocContent());
        }

        vo.setStatus(config.getStatus().getCode());
        vo.setStatusDesc(config.getStatus().getDesc());
        vo.setDocGeneratedAt(config.getDocGeneratedAt());
        vo.setCreatedAt(config.getCreatedAt());
        return vo;
    }
}
