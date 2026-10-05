package com.ecommerce.ordermanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.ordermanagement.repository.OutboxRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Integration tests for the outbox durability contract, against a real
 * PostgreSQL server.
 *
 * <p>The outbox exists so that "PostgreSQL committed" and "Elasticsearch will
 * hear about it" are one atomic fact. The interesting part is the state machine
 * the SQL implements: retryable until a bounded number of attempts, then
 * terminal; and settlement that settles exactly one event exactly once, which is
 * what makes redelivery safe.
 */
@SpringBootTest
@EnabledIf("com.ecommerce.ordermanagement.PostgresTestSupport#enabled")
class OutboxDurabilityIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerProperties(registry);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SchemaLoader schemaLoader;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        schemaLoader.apply();
        schemaLoader.truncateAll();
    }

    @Test
    @DisplayName("an outbox row stays retryable until MAX_ATTEMPTS, then goes terminal")
    void the_outbox_row_becomes_terminal_at_max_attempts() {
        long id = outboxRepository.enqueue(1L, "ORDER_CREATED");

        for (int i = 1; i < OutboxRepository.MAX_ATTEMPTS; i++) {
            assertThat(outboxRepository.markFailed(id, "es down")).isTrue();
            OutboxRepository.OutboxRow row = outboxRepository.get(id);
            assertThat(row.attempts()).isEqualTo(i);
            assertThat(row.processedAt()).as("still retryable before the limit").isNull();
            assertThat(row.lastError()).isEqualTo("es down");
        }

        assertThat(outboxRepository.markFailed(id, "es down")).isTrue();
        OutboxRepository.OutboxRow terminal = outboxRepository.get(id);
        assertThat(terminal.attempts()).isEqualTo(OutboxRepository.MAX_ATTEMPTS);
        assertThat(terminal.processedAt()).as("terminal once MAX_ATTEMPTS is reached").isNotNull();

        // A terminal row is no longer offered to the drain.
        assertThat(outboxRepository.claimUnprocessed(100)).noneMatch(e -> e.id() == id);

        // And it cannot be settled again: both transitions require
        // processed_at IS NULL, which is now false.
        assertThat(outboxRepository.markProcessed(id)).isFalse();
        assertThat(outboxRepository.markFailed(id, "late")).isFalse();
        assertThat(outboxRepository.get(id).attempts())
                .as("a terminal row must not keep accumulating attempts")
                .isEqualTo(OutboxRepository.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("markProcessed is idempotent, so duplicate delivery settles once")
    void settling_an_outbox_event_is_idempotent() {
        long id = outboxRepository.enqueue(42L, "ORDER_CREATED");

        assertThat(outboxRepository.markProcessed(id)).isTrue();
        assertThat(outboxRepository.markProcessed(id)).as("second settle is a no-op").isFalse();
        assertThat(outboxRepository.claimUnprocessed(100)).noneMatch(e -> e.id() == id);
        assertThat(outboxRepository.countPending(42L)).isZero();
    }

    @Test
    @DisplayName("a superseded event is terminal without burning attempts")
    void a_superseded_event_is_terminal_without_retry() {
        long id = outboxRepository.enqueue(7L, "ORDER_STATUS_CHANGED");

        assertThat(outboxRepository.markSuperseded(id, "stale version")).isTrue();

        OutboxRepository.OutboxRow row = outboxRepository.get(id);
        assertThat(row.processedAt()).isNotNull();
        assertThat(row.attempts()).as("superseded is not a failure").isZero();
        assertThat(row.lastError()).isEqualTo("stale version");
        assertThat(outboxRepository.lastError(7L)).isEqualTo("stale version");
    }

    @Test
    @DisplayName("the drain claims only unprocessed, under-limit rows")
    void the_drain_claims_only_eligible_rows() {
        long healthy = outboxRepository.enqueue(1L, "ORDER_CREATED");
        long terminal = outboxRepository.enqueue(2L, "ORDER_CREATED");
        long alreadyDone = outboxRepository.enqueue(3L, "ORDER_CREATED");
        outboxRepository.markProcessed(alreadyDone);
        for (int i = 0; i < OutboxRepository.MAX_ATTEMPTS; i++) {
            outboxRepository.markFailed(terminal, "es down");
        }

        var claimed = outboxRepository.claimUnprocessed(100);

        assertThat(claimed).extracting(OutboxRepository.PendingEvent::id).containsExactly(healthy);
        assertThat(outboxRepository.countPending(2L)).as("terminal row is not pending").isZero();
    }

    @Test
    @DisplayName("an order and its outbox event commit or roll back as one fact")
    void the_outbox_event_commits_with_the_order() {
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (name, email) VALUES ('Robin Roll', 'robin@example.com') RETURNING id",
                Long.class);

        // The application writes the order, its items and its outbox event inside
        // one transaction (OrderService uses TransactionTemplate). This test drives
        // the same thing through a real DataSource transaction, so a failure in
        // the outbox INSERT must take the order with it. Auto-commit must be off,
        // otherwise each statement would commit independently and the guarantee
        // under test would not actually be exercised.
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("INSERT INTO orders (user_id, status, total_amount) VALUES (?, 'PENDING', 10.00)",
                    userId);
            // Violates the event_type CHECK constraint.
            jdbc.update("INSERT INTO outbox (aggregate_id, event_type) VALUES (?, 'NOT_A_REAL_EVENT')",
                    1L);
            return null;
        })).isInstanceOf(Exception.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, userId))
                .as("the order must roll back with its outbox event")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE event_type = 'NOT_A_REAL_EVENT'", Integer.class))
                .isZero();

        // And the happy path does persist both, proving the transaction was real
        // rather than silently read-only.
        Long orderId = tx.execute(status -> {
            jdbc.update("INSERT INTO orders (user_id, status, total_amount) VALUES (?, 'PENDING', 10.00)",
                    userId);
            return jdbc.queryForObject(
                    "INSERT INTO outbox (aggregate_id, event_type) VALUES (?, 'ORDER_CREATED') RETURNING id",
                    Long.class, 1L);
        });
        assertThat(orderId).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, userId))
                .as("a committed transaction keeps both facts")
                .isEqualTo(1);
    }
}