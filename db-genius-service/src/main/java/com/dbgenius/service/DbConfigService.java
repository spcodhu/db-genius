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

    /**
     * 手动刷新数据库文档。
     *
     * <p><b>设计意图：</b>数据库结构在配置生成后可能发生变更，本方法允许用户手动触发
     * 「重新验证连接 + 重新生成文档」。实现上复用异步验证链路（发送
     * {@code REFRESH_DOC} 动作消息到 MQ，由消费者执行
     * {@link #autoVerifyAndGenerateDoc}），与配置创建/更新时的链路完全一致，
     * 避免重复实现，且刷新过程不阻塞请求线程。</p>
     *
     * @param userId   当前登录用户 ID
     * @param configId 数据库配置 ID
     */
    void refreshDoc(Long userId, Long configId);
}
