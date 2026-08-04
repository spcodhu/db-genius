package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.entity.ModelProvider;
import com.dbgenius.model.vo.ModelProviderVO;

import java.util.List;

/**
 * 模型提供商预设服务。
 */
public interface ModelProviderService extends IService<ModelProvider> {

    /**
     * 获取所有可用的 provider 预设列表（含内置 + 管理员扩展），按 sortOrder 升序。
     */
    List<ModelProviderVO> listProviders();

    /**
     * 系统启动 / schema 初始化后调用：如果预设表为空，自动插入内置条目。
     */
    void initBuiltinProviders();
}
