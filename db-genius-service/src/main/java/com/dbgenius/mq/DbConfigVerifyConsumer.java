package com.dbgenius.mq;

import com.dbgenius.service.DbConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbConfigVerifyConsumer {

    private final DbConfigService dbConfigService;

    /**
     * Exceptions are rethrown so the container retries (local retry),
     * and the message lands in the DLQ after retries are exhausted.
     */
    @RabbitListener(queues = DbConfigMqConstants.QUEUE_VERIFY)
    public void onMessage(DbConfigVerifyMessage message) {
        // 兼容旧消息（action 为 null）：一律按 VERIFY_AND_DOC 处理
        String action = message.action() != null ? message.action() : DbConfigMqConstants.ACTION_VERIFY_AND_DOC;
        log.info("Received db-config verify message, configId={}, action={}", message.configId(), action);
        // 两种动作的消费逻辑一致：autoVerifyAndGenerateDoc 本身即「重新验证连接 + 重新生成文档」
        dbConfigService.autoVerifyAndGenerateDoc(message.configId());
    }
}
