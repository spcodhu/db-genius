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
        try {
            rabbitTemplate.convertAndSend(
                    DbConfigMqConstants.EXCHANGE,
                    DbConfigMqConstants.ROUTING_KEY_VERIFY,
                    new DbConfigVerifyMessage(configId));
            log.info("Sent db-config verify message, configId={}", configId);
        } catch (Exception e) {
            log.error("Failed to send db-config verify message, configId={}", configId, e);
        }
    }
}
