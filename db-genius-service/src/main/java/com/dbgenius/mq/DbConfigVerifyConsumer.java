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
        log.info("Received db-config verify message, configId={}", message.configId());
        dbConfigService.autoVerifyAndGenerateDoc(message.configId());
    }
}
