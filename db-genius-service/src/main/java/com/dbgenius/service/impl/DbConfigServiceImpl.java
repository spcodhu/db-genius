package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.mapper.DbConfigMapper;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.model.enums.DbType;
import com.dbgenius.model.vo.DbConfigVO;
import com.dbgenius.mq.DbConfigMqConstants;
import com.dbgenius.mq.DbConfigVerifyProducer;
import com.dbgenius.service.DbConfigService;
import com.dbgenius.service.database.DatabaseAdapterRegistry;
import com.dbgenius.service.database.DatabaseDocRenderer;
import com.dbgenius.trial.TrialDeny;
import com.dbgenius.trial.TrialGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class DbConfigServiceImpl extends ServiceImpl<DbConfigMapper, DbConfig> implements DbConfigService {

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    @org.springframework.beans.factory.annotation.Autowired
    private TrialGuard trialGuard;

    @org.springframework.beans.factory.annotation.Autowired
    private DbConfigVerifyProducer dbConfigVerifyProducer;

    @org.springframework.beans.factory.annotation.Autowired
    private DatabaseAdapterRegistry databaseAdapterRegistry;

    @Override
    @TrialDeny("试用版暂不支持新增数据库配置")
    public DbConfigVO createConfig(Long userId, DbConfigRequest request) {
        // 归一化数据库类型（null/空白回退 mysql，不识别直接 400），具体必填项由适配器按类型校验
        DbType type = DbType.fromCode(request.getDbType());
        databaseAdapterRegistry.getAdapter(type).validateRequest(request);
        DbConfig config = new DbConfig();
        config.setUserId(userId);
        config.setName(request.getName());
        config.setDbType(type.getCode());
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDbName(request.getDbName());
        config.setUsername(request.getUsername());
        config.setPasswordEncrypted(encryptPasswordIfPresent(request.getPassword()));
        config.setStatus(DbConfigStatus.VERIFYING);
        save(config);
        dbConfigVerifyProducer.send(config.getId());
        return toVO(config);
    }

    @Override
    public DbConfigVO updateConfig(Long userId, Long configId, DbConfigRequest request) {
        DbConfig config = getConfigEntity(userId, configId);
        trialGuard.denyIfTrialBuiltin(config);
        // 归一化数据库类型，具体必填项由适配器按类型校验
        DbType type = DbType.fromCode(request.getDbType());
        databaseAdapterRegistry.getAdapter(type).validateRequest(request);
        config.setName(request.getName());
        config.setDbType(type.getCode());
        config.setHost(request.getHost());
        config.setPort(request.getPort());
        config.setDbName(request.getDbName());
        config.setUsername(request.getUsername());
        config.setPasswordEncrypted(encryptPasswordIfPresent(request.getPassword()));
        config.setStatus(DbConfigStatus.VERIFYING);
        config.setDocContent(null);
        config.setDocGeneratedAt(null);
        updateById(config);
        dbConfigVerifyProducer.send(config.getId());
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

    /**
     * 手动刷新数据库文档。
     *
     * <p><b>设计说明：</b>复用异步验证链路——先把状态置为 VERIFYING，
     * 再发送 {@code REFRESH_DOC} 动作消息到 MQ，由消费者执行
     * {@link #autoVerifyAndGenerateDoc(Long)}（重新验证连接并重新生成文档）。
     * 与配置创建/更新时的处理链路完全一致，避免重复实现，且刷新过程不阻塞请求线程。</p>
     */
    @Override
    public void refreshDoc(Long userId, Long configId) {
        DbConfig config = getConfigEntity(userId, configId);
        trialGuard.denyIfTrialBuiltin(config);
        config.setStatus(DbConfigStatus.VERIFYING);
        updateById(config);
        dbConfigVerifyProducer.send(configId, DbConfigMqConstants.ACTION_REFRESH_DOC);
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
        // SQLite 等无密码场景下密文为空，直接返回 null 供适配器按类型处理
        String encrypted = config.getPasswordEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return AesUtil.decrypt(encrypted, encryptKey);
    }

    /**
     * 密码为空时密文存 null（SQLite 无账密、MongoDB 账密可空），否则 AES 加密存储。
     */
    private String encryptPasswordIfPresent(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        return AesUtil.encrypt(password, encryptKey);
    }

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

    /**
     * 按配置的数据库类型选择适配器测试连接（JDBC 直连逻辑已下沉到适配器层）。
     */
    private boolean tryConnect(DbConfig config) {
        return databaseAdapterRegistry.getAdapter(config.getDbType())
                .testConnection(config, getDecryptedPassword(config));
    }

    /**
     * 抽取 Schema 元数据并渲染为文档。
     *
     * <p>JDBC 元数据拼接逻辑已下沉到适配器层（{@code extractSchema}）；
     * 元数据读取错误已由适配器写入 {@code SchemaMetadata.errorMessage}，
     * 由 {@link DatabaseDocRenderer#render} 输出到文档中，与旧实现
     * 「吞掉异常把错误拼进文档」的语义一致。</p>
     */
    private String buildDatabaseDoc(DbConfig config) {
        return DatabaseDocRenderer.render(databaseAdapterRegistry.getAdapter(config.getDbType())
                .extractSchema(config, getDecryptedPassword(config)));
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
