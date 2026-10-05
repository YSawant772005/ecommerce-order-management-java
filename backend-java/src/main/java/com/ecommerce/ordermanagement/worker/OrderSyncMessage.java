package com.ecommerce.ordermanagement.worker;

/**
 * The RabbitMQ message: {@code order_id} says what to index, {@code outbox_id}
 * says which event row to settle. Dispatching {@code order_id} alone would leave
 * the worker unable to mark the right row.
 */
public record OrderSyncMessage(long orderId, Long outboxId) {
}
