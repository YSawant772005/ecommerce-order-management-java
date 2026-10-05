package com.ecommerce.ordermanagement.worker;

import com.ecommerce.ordermanagement.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands a committed event to the queue.
 *
 * <p>Called only after COMMIT. Best-effort by design: if RabbitMQ is down the
 * committed outbox row stays pending and the 15s drain re-dispatches it, so
 * checkout never depends on the broker.</p>
 */
@Component
public class SyncDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SyncDispatcher.class);

    private final RabbitTemplate rabbitTemplate;

    public SyncDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void dispatch(long orderId, Long outboxId) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.SYNC_QUEUE, new OrderSyncMessage(orderId, outboxId));
        } catch (Exception exc) {
            log.warn("dispatch failed for order {} (outbox {}); drain will retry: {}",
                    orderId, outboxId, exc.getMessage());
        }
    }
}
