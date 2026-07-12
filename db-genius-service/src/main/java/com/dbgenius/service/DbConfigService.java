package com.dbgenius.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dbgenius.model.dto.DbConfigRequest;
import com.dbgenius.model.entity.DbConfig;
import com.dbgenius.model.vo.DbConfigVO;

import java.util.List;

public interface DbConfigService extends IService<DbConfig> {

    DbConfigVO createConfig(Long userId, DbConfigRequest request);

    DbConfigVO updateConfig(Long userId, Long configId, DbConfigRequest request);

    void deleteConfig(Long userId, Long configId);

    List<DbConfigVO> listConfigs(Long userId);

    DbConfigVO getConfig(Long userId, Long configId);

    boolean testConnection(Long userId, Long configId);

    String generateDoc(Long userId, Long configId);

    String getDocContent(Long userId, Long configId);

    boolean isBuiltinConfig(Long userId, Long configId);

    void autoVerifyAndGenerateDoc(Long configId);

    void validateConfigForChat(Long userId, Long configId);
}
