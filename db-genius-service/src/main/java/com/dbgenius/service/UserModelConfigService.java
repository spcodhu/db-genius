package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.dto.UserModelConfigRequest;
import com.dbgenius.model.entity.UserModelConfig;
import com.dbgenius.model.vo.UserModelConfigVO;

import java.util.List;

/**
 * 用户模型配置服务。
 */
public interface UserModelConfigService extends IService<UserModelConfig> {

    /**
     * 新增用户模型配置。
     *
     * @param userId  当前用户 ID
     * @param request 配置请求（含明文 apiKey）
     * @return 创建的配置（不含密钥）
     */
    UserModelConfigVO createConfig(Long userId, UserModelConfigRequest request);

    /**
     * 编辑用户模型配置。
     *
     * @param userId  当前用户 ID
     * @param configId 配置 ID
     * @param request 配置请求（apiKey 为空时沿用旧值不更新）
     * @return 更新后的配置（不含密钥）
     */
    UserModelConfigVO updateConfig(Long userId, Long configId, UserModelConfigRequest request);

    /**
     * 删除用户模型配置。
     */
    void deleteConfig(Long userId, Long configId);

    /**
     * 列出用户所有模型配置。
     */
    List<UserModelConfigVO> listConfigs(Long userId);

    /**
     * 获取单个配置详情（不含密钥）。
     */
    UserModelConfigVO getConfig(Long userId, Long configId);

    /**
     * 设为默认配置。
     */
    void setDefault(Long userId, Long configId);

    /**
     * 获取用户当前生效的模型配置实体（含解密后的 apiKey 明文）。
     * 优先取 is_default=true 的，否则取最新启用的一条。
     *
     * @param userId 用户 ID
     * @return 包含明文 apiKey 的配置实体（仅内部使用，不对外暴露）
     * @throws com.dbgenius.common.exception.BusinessException 当用户无可用配置时抛出
     */
    UserModelConfig getActiveConfig(Long userId);
}
