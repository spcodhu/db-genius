package com.dbgenius.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbConfigVerifyProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Sends a verify & generate-doc task for the given config.
     * Send failures are logged but not propagated, so config creation/update is not affected.
     */
    public void send(Long configId) {
        send(configId, DbConfigMqConstants.ACTION_VERIFY_AND_DOC);
    }

    /**
     * 按指定动作发送「验证 + 生成文档」任务。
     *
     * <p>发送失败仅记录日志不上抛，不影响主流程（配置创建/更新/刷新均不受 MQ 故障阻断）。</p>
     *
     * @param configId 数据库配置 ID
     * @param action   动作类型（{@code VERIFY_AND_DOC} 或 {@code REFRESH_DOC}）
     */
    public void send(Long configId, String action) {
        try {
            rabbitTemplate.convertAndSend(
                    DbConfigMqConstants.EXCHANGE,
                    DbConfigMqConstants.ROUTING_KEY_VERIFY,
                    new DbConfigVerifyMessage(configId, action));
            log.info("Sent db-config verify message, configId={}, action={}", configId, action);
        } catch (Exception e) {
            log.error("Failed to send db-config verify message, configId={}, action={}", configId, action, e);
        }
    }
}
