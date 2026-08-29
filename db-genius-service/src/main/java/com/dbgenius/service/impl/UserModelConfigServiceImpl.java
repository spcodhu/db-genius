package com.dbgenius.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dbgenius.common.exception.BusinessException;
import com.dbgenius.common.exception.ErrorCode;
import com.dbgenius.common.util.AesUtil;
import com.dbgenius.mapper.UserModelConfigMapper;
import com.dbgenius.model.dto.UserModelConfigRequest;
import com.dbgenius.model.entity.ModelProvider;
import com.dbgenius.model.entity.UserModelConfig;
import com.dbgenius.model.enums.ModelConfigStatus;
import com.dbgenius.model.enums.ModelProviderType;
import com.dbgenius.model.vo.ContextWindowLookupVO;
import com.dbgenius.model.vo.UserModelConfigVO;
import com.dbgenius.service.ModelProviderService;
import com.dbgenius.service.UserModelConfigService;
import com.dbgenius.service.modelinfo.KnownModelRegistry;
import com.dbgenius.service.modelinfo.ModelInfoService;
import com.dbgenius.trial.TrialDeny;
import com.dbgenius.trial.TrialGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class UserModelConfigServiceImpl extends ServiceImpl<UserModelConfigMapper, UserModelConfig>
        implements UserModelConfigService {

    @Value("${db-genius.encrypt-key}")
    private String encryptKey;

    /** 系统级兜底 API Key（yml: db-genius.model.default.api-key） */
    @Value("${db-genius.model.default.api-key:}")
    private String defaultApiKey;

    /** 系统级兜底 Base URL */
    @Value("${db-genius.model.default.base-url:https://api.deepseek.com}")
    private String defaultBaseUrl;

    /** 系统级兜底模型名 */
    @Value("${db-genius.model.default.model:deepseek-v4-pro}")
    private String defaultModel;

    @Autowired
    private TrialGuard trialGuard;

    @Autowired
    private ModelProviderService modelProviderService;

    @Autowired
    private KnownModelRegistry knownModelRegistry;

    @Autowired
    private ModelInfoService modelInfoService;

    @Override
    @Transactional
    @TrialDeny(ErrorCode.TRIAL_MODEL_CONFIG_CREATE)
    public UserModelConfigVO createConfig(Long userId, UserModelConfigRequest request) {
        UserModelConfig config = new UserModelConfig();
        config.setUserId(userId);
        config.setProviderCode(request.getProviderCode());
        config.setProviderType(ModelProviderType.OPENAI_COMPATIBLE);
        config.setDisplayName(request.getDisplayName());

        // baseUrl：前端传了就用；否则从 provider 预设中取
        String baseUrl = request.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = resolveBaseUrlFromProvider(request.getProviderCode());
        }
        config.setBaseUrl(baseUrl);

        config.setApiKeyEncrypted(AesUtil.encrypt(request.getApiKey(), encryptKey));
        config.setModelName(request.getModelName());
        config.setContextWindow(resolveContextWindow(request.getContextWindow(), request.getModelName(), null));

        // 如果是用户第一条配置，自动设为默认
        long count = countByUserId(userId);
        config.setIsDefault(count == 0);
        config.setStatus(ModelConfigStatus.ENABLED);
        save(config);
        log.info("User {} created model config id={}, provider={}", userId, config.getId(), config.getProviderCode());
        return toVO(config);
    }

    @Override
    @Transactional
    @TrialDeny(ErrorCode.TRIAL_MODEL_CONFIG_UPDATE)
    public UserModelConfigVO updateConfig(Long userId, Long configId, UserModelConfigRequest request) {
        UserModelConfig config = getOwnedConfig(userId, configId);
        config.setProviderCode(request.getProviderCode());
        config.setDisplayName(request.getDisplayName());

        String baseUrl = request.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = resolveBaseUrlFromProvider(request.getProviderCode());
        }
        config.setBaseUrl(baseUrl);

        // apiKey 不为空才更新（前端可能不传以保持旧值）
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            config.setApiKeyEncrypted(AesUtil.encrypt(request.getApiKey(), encryptKey));
        }
        config.setModelName(request.getModelName());
        config.setContextWindow(resolveContextWindow(request.getContextWindow(), request.getModelName(),
                config.getContextWindow()));
        updateById(config);
        log.info("User {} updated model config id={}", userId, configId);
        return toVO(config);
    }

    @Override
    @Transactional
    @TrialDeny(ErrorCode.TRIAL_MODEL_CONFIG_DELETE)
    public void deleteConfig(Long userId, Long configId) {
        UserModelConfig config = getOwnedConfig(userId, configId);
        if (Boolean.TRUE.equals(config.getIsDefault())) {
            throw new BusinessException(ErrorCode.CANNOT_DELETE_DEFAULT_CONFIG);
        }
        removeById(configId);
        log.info("User {} deleted model config id={}", userId, configId);
    }

    @Override
    public List<UserModelConfigVO> listConfigs(Long userId) {
        return list(new LambdaQueryWrapper<UserModelConfig>()
                .eq(UserModelConfig::getUserId, userId)
                .orderByDesc(UserModelConfig::getIsDefault)
                .orderByDesc(UserModelConfig::getCreatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public UserModelConfigVO getConfig(Long userId, Long configId) {
        UserModelConfig config = getOwnedConfig(userId, configId);
        return toVO(config);
    }

    @Override
    @Transactional
    @TrialDeny(ErrorCode.TRIAL_MODEL_CONFIG_SET_DEFAULT)
    public void setDefault(Long userId, Long configId) {
        UserModelConfig config = getOwnedConfig(userId, configId);
        if (config.getStatus() != ModelConfigStatus.ENABLED) {
            throw new BusinessException(ErrorCode.DISABLED_CONFIG_CANNOT_BE_DEFAULT);
        }
        // 先取消用户所有默认（set 仅存在于 LambdaUpdateWrapper）
        update(new LambdaUpdateWrapper<UserModelConfig>()
                .eq(UserModelConfig::getUserId, userId)
                .eq(UserModelConfig::getIsDefault, true)
                .set(UserModelConfig::getIsDefault, false));
        // 再设置
        config.setIsDefault(true);
        updateById(config);
        log.info("User {} set default model config id={}", userId, configId);
    }

    @Override
    public UserModelConfig getActiveConfig(Long userId) {
        // 优先取用户设为默认且启用状态的
        UserModelConfig config = getOne(new LambdaQueryWrapper<UserModelConfig>()
                .eq(UserModelConfig::getUserId, userId)
                .eq(UserModelConfig::getIsDefault, true)
                .eq(UserModelConfig::getStatus, ModelConfigStatus.ENABLED.getCode()));

        if (config == null) {
            // 退而求其次：取最新启用的一条
            config = getOne(new LambdaQueryWrapper<UserModelConfig>()
                    .eq(UserModelConfig::getUserId, userId)
                    .eq(UserModelConfig::getStatus, ModelConfigStatus.ENABLED.getCode())
                    .orderByDesc(UserModelConfig::getCreatedAt)
                    .last("LIMIT 1"));
        }

        if (config == null) {
            // 用户没有自定义配置：fallback 到系统级配置
            if (defaultApiKey == null || defaultApiKey.isBlank()) {
                throw new BusinessException(ErrorCode.NO_MODEL_CONFIGURED);
            }
            log.info("User {} has no model config, falling back to system default", userId);
            return buildFallbackConfig(userId);
        }

        return config;
    }

    @Override
    @TrialDeny(ErrorCode.TRIAL_CONTEXT_WINDOW_LOOKUP)
    public ContextWindowLookupVO lookupContextWindow(Long userId, Long configId) {
        UserModelConfig config = getOwnedConfig(userId, configId);
        // fallback/明文场景与 ChatModelFactory.resolveApiKey 保持一致
        String apiKey = "system".equals(config.getProviderCode())
                ? config.getApiKeyEncrypted()
                : AesUtil.decrypt(config.getApiKeyEncrypted(), encryptKey);
        return modelInfoService.lookup(config.getBaseUrl(), apiKey, config.getModelName());
    }

    /**
     * 上下文窗口取值优先级：用户显式填写 → 已知模型注册表兜底 → 沿用旧值（编辑场景）。
     */
    private Integer resolveContextWindow(Integer requested, String modelName, Integer existing) {
        if (requested != null) {
            return requested;
        }
        return knownModelRegistry.lookup(modelName).orElse(existing);
    }

    // ---- private helpers ----

    private UserModelConfig getOwnedConfig(Long userId, Long configId) {
        UserModelConfig config = getById(configId);
        if (config == null || !config.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.MODEL_CONFIG_NOT_FOUND);
        }
        return config;
    }

    private long countByUserId(Long userId) {
        return count(new LambdaQueryWrapper<UserModelConfig>()
                .eq(UserModelConfig::getUserId, userId));
    }

    private String resolveBaseUrlFromProvider(String providerCode) {
        if (providerCode == null || "custom".equals(providerCode)) {
            return "";
        }
        ModelProvider provider = modelProviderService.getOne(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getProviderCode, providerCode));
        if (provider != null && provider.getDefaultBaseUrl() != null) {
            return provider.getDefaultBaseUrl();
        }
        return "";
    }

    /**
     * 当用户无自定义配置时，用系统级兜底 key/baseUrl/model 构造一个虚拟配置。
     * 此对象不落库，仅供内部调用。
     */
    private UserModelConfig buildFallbackConfig(Long userId) {
        UserModelConfig config = new UserModelConfig();
        config.setUserId(userId);
        config.setProviderCode("system");
        config.setProviderType(ModelProviderType.OPENAI_COMPATIBLE);
        config.setDisplayName("系统默认");
        config.setBaseUrl(defaultBaseUrl);
        config.setApiKeyEncrypted(defaultApiKey); // fallback key 不加密，明文就存在这里（仅内部调用 decryptFallback 区别对待）
        config.setModelName(defaultModel);
        config.setContextWindow(knownModelRegistry.lookup(defaultModel).orElse(null));
        config.setIsDefault(true);
        config.setStatus(ModelConfigStatus.ENABLED);
        return config;
    }

    private UserModelConfigVO toVO(UserModelConfig entity) {
        UserModelConfigVO vo = new UserModelConfigVO();
        vo.setId(entity.getId());
        vo.setProviderCode(entity.getProviderCode());
        vo.setProviderType(entity.getProviderType().getCode());
        vo.setDisplayName(entity.getDisplayName());
        vo.setBaseUrl(entity.getBaseUrl());
        vo.setModelName(entity.getModelName());
        vo.setContextWindow(entity.getContextWindow());
        vo.setIsDefault(entity.getIsDefault());
        vo.setStatus(entity.getStatus().getCode());
        vo.setStatusDesc(entity.getStatus().getDesc());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
