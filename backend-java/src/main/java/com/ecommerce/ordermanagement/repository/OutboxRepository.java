package com.ecommerce.ordermanagement.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Outbox durability record for Strategy 1.
 *
 * <p>Written <b>inside</b> the order transaction, so "PostgreSQL committed" and
 * "Elasticsearch will hear about it" are a single atomic fact. Every settlement
 * targets the exact event id and is compare-and-set
 * ({@code WHERE id=? AND processed_at IS NULL}), so a redelivered worker is
 * idempotent and can never settle a sibling event for the same order.</p>
 */
@Repository
public class OutboxRepository {

    /** Retries are bounded; after this many failures the row goes terminal. */
    public static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(
            long id,
            long aggregateId,
            String eventType,
            OffsetDateTime createdAt,
            OffsetDateTime processedAt,
            int attempts,
            String lastError) {
    }

    public record PendingEvent(long id, long aggregateId) {
    }

    public long enqueue(long aggregateId, String eventType) {
        Long id = jdbc.queryForObject(
                "INSERT INTO outbox (aggregate_id, event_type) VALUES (?, ?) RETURNING id",
                Long.class, aggregateId, eventType);
        return id == null ? 0L : id;
    }

    public OutboxRow get(long outboxId) {
        var rows = jdbc.query(
                "SELECT id, aggregate_id, event_type, created_at, processed_at, attempts, last_error"
                        + " FROM outbox WHERE id = ?",
                (rs, n) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getLong("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("processed_at", OffsetDateTime.class),
                        rs.getInt("attempts"),
                        rs.getString("last_error")),
                outboxId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Oldest pending events, skipping locked, processed and terminal rows. */
    public List<PendingEvent> claimUnprocessed(int limit) {
        return jdbc.query(
                "SELECT id, aggregate_id FROM outbox"
                        + " WHERE processed_at IS NULL AND attempts < ?"
                        + " ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new PendingEvent(rs.getLong("id"), rs.getLong("aggregate_id")),
                MAX_ATTEMPTS, limit);
    }

    /** Settle exactly this event. Idempotent: a second call settles nothing. */
    public boolean markProcessed(long outboxId) {
        return jdbc.update(
                "UPDATE outbox SET processed_at = now() WHERE id = ? AND processed_at IS NULL",
                outboxId) > 0;
    }

    /** Record a retryable failure. Terminal once attempts reach {@link #MAX_ATTEMPTS}. */
    public boolean markFailed(long outboxId, String error) {
        return jdbc.update(
                "UPDATE outbox SET attempts = attempts + 1, last_error = ?,"
                        + " processed_at = CASE WHEN attempts + 1 >= ? THEN now() ELSE processed_at END"
                        + " WHERE id = ? AND processed_at IS NULL",
                error, MAX_ATTEMPTS, outboxId) > 0;
    }

    /** Terminal without retry: a newer version already won, retrying is futile. */
    public boolean markSuperseded(long outboxId, String reason) {
        return jdbc.update(
                "UPDATE outbox SET processed_at = now(), last_error = ?"
                        + " WHERE id = ? AND processed_at IS NULL",
                reason, outboxId) > 0;
    }

    /** Pending events for one order — what the sync badge reports. */
    public long countPending(long aggregateId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND processed_at IS NULL",
                Long.class, aggregateId);
        return count == null ? 0L : count;
    }

    /** The most recent recorded error for one order, or {@code null}. */
    public String lastError(long aggregateId) {
        var rows = jdbc.queryForList(
                "SELECT last_error FROM outbox WHERE aggregate_id = ? AND last_error IS NOT NULL"
                        + " ORDER BY id DESC LIMIT 1",
                String.class, aggregateId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
