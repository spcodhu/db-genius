package com.dbgenius.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dbgenius.common.result.R;
import com.dbgenius.model.dto.UserModelConfigRequest;
import com.dbgenius.model.vo.ModelProviderVO;
import com.dbgenius.model.vo.UserModelConfigVO;
import com.dbgenius.service.ModelProviderService;
import com.dbgenius.service.UserModelConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型配置管理 API。
 */
@Slf4j
@RestController
@RequestMapping("/model-config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelProviderService modelProviderService;
    private final UserModelConfigService userModelConfigService;

    /**
     * 获取所有可用的 provider 预设（内置 + 管理员扩展）。
     */
    @GetMapping("/providers")
    public R<List<ModelProviderVO>> listProviders() {
        return R.ok(modelProviderService.listProviders());
    }

    /**
     * 列出当前用户的所有模型配置。
     */
    @GetMapping("/configs")
    public R<List<UserModelConfigVO>> listConfigs() {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(userModelConfigService.listConfigs(userId));
    }

    /**
     * 新增模型配置。
     */
    @PostMapping("/configs")
    public R<UserModelConfigVO> createConfig(@Valid @RequestBody UserModelConfigRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(userModelConfigService.createConfig(userId, request));
    }

    /**
     * 编辑模型配置。
     */
    @PutMapping("/configs/{id}")
    public R<UserModelConfigVO> updateConfig(@PathVariable Long id,
                                             @Valid @RequestBody UserModelConfigRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(userModelConfigService.updateConfig(userId, id, request));
    }

    /**
     * 删除模型配置。
     */
    @DeleteMapping("/configs/{id}")
    public R<Void> deleteConfig(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        userModelConfigService.deleteConfig(userId, id);
        return R.ok();
    }

    /**
     * 设为默认配置。
     */
    @PutMapping("/configs/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        userModelConfigService.setDefault(userId, id);
        return R.ok();
    }

    /**
     * 获取当前生效的配置概要。
     */
    @GetMapping("/active")
    public R<UserModelConfigVO> getActiveConfig() {
        Long userId = StpUtil.getLoginIdAsLong();
        var config = userModelConfigService.getActiveConfig(userId);

        // fallback 配置（system provider）不在数据库里，直接转 VO
        if ("system".equals(config.getProviderCode())) {
            UserModelConfigVO vo = new UserModelConfigVO();
            vo.setId(null);
            vo.setProviderCode(config.getProviderCode());
            vo.setProviderType(config.getProviderType().getCode());
            vo.setDisplayName(config.getDisplayName());
            vo.setBaseUrl(config.getBaseUrl());
            vo.setModelName(config.getModelName());
            vo.setIsDefault(true);
            vo.setStatus(1);
            vo.setStatusDesc("已启用");
            return R.ok(vo);
        }

        return R.ok(userModelConfigService.getConfig(userId, config.getId()));
    }
}
