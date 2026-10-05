package com.ecommerce.ordermanagement.model;

/**
 * Sync badge vocabulary — a statement about <b>reality</b>, comparing the
 * committed PostgreSQL {@code version} against what Elasticsearch holds.
 * Deliberately disjoint from the placement vocabulary ({@code QUEUED}) that
 * {@code POST /api/orders} reports.
 */
public record SyncStatusOut(
        long order_id,
        String state,
        int pg_version,
        Integer es_version,
        long pending_outbox_events,
        String last_error) {
}
