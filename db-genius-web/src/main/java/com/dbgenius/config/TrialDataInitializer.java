package com.dbgenius.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.mapper.DbConfigMapper;
import com.dbgenius.mapper.SysUserMapper;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.entity.SysUser;
import com.dbgenius.model.enums.DbConfigStatus;
import com.dbgenius.model.enums.DbType;
import com.dbgenius.mq.DbConfigVerifyProducer;
import com.dbgenius.trial.TrialProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 试用版内置数据库配置初始化器。
 * 仅在 db-genius.trial.enabled=true 时生效，自动为 admin 用户创建一条内置的 db-genius 配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "db-genius.trial.enabled", havingValue = "true")
public class TrialDataInitializer implements CommandLineRunner {

    private final TrialProperties trialProperties;
    private final SysUserMapper sysUserMapper;
    private final DbConfigMapper dbConfigMapper;
    private final DbConfigVerifyProducer dbConfigVerifyProducer;

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    @Override
    public void run(String... args) {
        if (!hasRequiredConnectionInfo()) {
            log.warn("Trial mode is enabled but built-in database connection info is incomplete. "
                    + "Skipping built-in config initialization.");
            return;
        }

        SysUser admin = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (admin == null) {
            log.warn("Default admin user not found, cannot create trial built-in database config.");
            return;
        }

        long existingCount = dbConfigMapper.selectCount(new LambdaQueryWrapper<DbConfig>()
                .eq(DbConfig::getBuiltin, true));
        if (existingCount > 0) {
            log.info("Trial built-in database config already exists, skipping initialization.");
            return;
        }

        DbConfig config = new DbConfig();
        config.setUserId(admin.getId());
        config.setName(trialProperties.getBuiltinDbName());
        config.setDbType(DbType.MYSQL.getCode());
        config.setHost(trialProperties.getBuiltinHost());
        config.setPort(trialProperties.getBuiltinPort());
        config.setDbName(trialProperties.getBuiltinDbName());
        config.setUsername(trialProperties.getBuiltinUsername());
        config.setPasswordEncrypted(AesUtil.encrypt(trialProperties.getBuiltinPassword(), encryptKey));
        config.setStatus(DbConfigStatus.VERIFYING);
        config.setBuiltin(true);

        dbConfigMapper.insert(config);
        log.info("Trial built-in database config created for admin user, configId={}", config.getId());

        dbConfigVerifyProducer.send(config.getId());
    }

    private boolean hasRequiredConnectionInfo() {
        return StringUtils.hasText(trialProperties.getBuiltinHost())
                && trialProperties.getBuiltinPort() != null
                && StringUtils.hasText(trialProperties.getBuiltinUsername())
                && StringUtils.hasText(trialProperties.getBuiltinPassword());
    }
}
