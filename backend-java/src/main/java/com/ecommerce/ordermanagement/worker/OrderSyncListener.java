package com.ecommerce.ordermanagement.worker;

import com.ecommerce.ordermanagement.config.RabbitConfig;
import com.ecommerce.ordermanagement.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The background task execution the former Celery worker performed.
 *
 * <p>Retries do not come from redelivery loops: a stale version is terminal and
 * an outage stays pending in the outbox, which the scheduled drain re-dispatches.
 * The listener therefore acknowledges the message and lets the outbox own
 * durability.</p>
 */
@Component
public class OrderSyncListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSyncListener.class);

    private final SyncService syncService;

    public OrderSyncListener(SyncService syncService) {
        this.syncService = syncService;
    }

    @RabbitListener(queues = RabbitConfig.SYNC_QUEUE)
    public void onMessage(OrderSyncMessage message) {
        try {
            syncService.indexOrder(message.orderId(), message.outboxId());
        } catch (Exception exc) {
            log.warn("index order {} failed; outbox {} stays pending for the drain: {}",
                    message.orderId(), message.outboxId(), exc.getMessage());
        }
    }
}
