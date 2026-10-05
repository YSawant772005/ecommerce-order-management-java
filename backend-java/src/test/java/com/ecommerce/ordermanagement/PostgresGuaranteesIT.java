package com.ecommerce.ordermanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.ordermanagement.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Integration tests for the guarantees that live in PostgreSQL rather than in
 * Java. These run against a real PostgreSQL server because the behaviour under
 * test <em>is</em> the database behaviour: a trigger refusing an UPDATE, a CHECK
 * constraint rejecting a bad status. A mocked JdbcTemplate would assert nothing
 * about any of that.
 */
@SpringBootTest
@EnabledIf("com.ecommerce.ordermanagement.PostgresTestSupport#enabled")
class PostgresGuaranteesIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerProperties(registry);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SchemaLoader schemaLoader;

    @Autowired
    private OrderRepository orders;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        schemaLoader.apply();
        schemaLoader.truncateAll();
    }

    private long insertUser(String name, String email) {
        Long id = jdbc.queryForObject(
                "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id", Long.class, name, email);
        return id == null ? 0L : id;
    }

    // -----------------------------------------------------------------
    // 1. Snapshot immutability when the catalog changes
    // -----------------------------------------------------------------

    @Test
    @DisplayName("order_items title and unit_price are snapshots and cannot be rewritten")
    void the_order_item_snapshot_cannot_be_rewritten() {
        long userId = insertUser("Wendy Wireless", "wendy@example.com");
        long orderId = orders.insertOrder(userId, new BigDecimal("59.98"), "PENDING");
        jdbc.update("INSERT INTO order_items (order_id, product_id, title, quantity, unit_price)"
                + " VALUES (?, ?, ?, ?, ?)",
                orderId, "prod-mouse", "Wireless Mouse", 2, new BigDecimal("29.99"));

        // A later catalog edit must not rewrite the captured financial record.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE order_items SET unit_price = ? WHERE order_id = ?",
                new BigDecimal("0.01"), orderId))
                .hasMessageContaining("snapshot is immutable");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE order_items SET title = ? WHERE order_id = ?", "Cheap Mouse", orderId))
                .hasMessageContaining("snapshot is immutable");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, unit_price FROM order_items WHERE order_id = ?", orderId);
        assertThat(row.get("title")).isEqualTo("Wireless Mouse");
        assertThat(((BigDecimal) row.get("unit_price")).compareTo(new BigDecimal("29.99"))).isZero();

        // Non-snapshot columns stay freely updatable, which proves the trigger
        // is targeted rather than a blanket row lock.
        jdbc.update("UPDATE order_items SET quantity = 3 WHERE order_id = ?", orderId);
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an order total agrees with its own items without consulting the catalog")
    void the_order_total_agrees_with_its_own_items() {
        long userId = insertUser("Riley Rigid", "riley@example.com");
        long orderId = orders.insertOrder(userId, new BigDecimal("59.98"), "PENDING");
        orders.insertOrderItems(orderId, List.of(
                new OrderRepository.Line("prod-mouse", "Wireless Mouse", 2, new BigDecimal("29.99")),
                new OrderRepository.Line("prod-pad", "Mouse Pad", 1, new BigDecimal("0.00"))));

        BigDecimal sum = jdbc.queryForObject(
                "SELECT SUM(quantity * unit_price) FROM order_items WHERE order_id = ?",
                BigDecimal.class, orderId);
        BigDecimal total = jdbc.queryForObject(
                "SELECT total_amount FROM orders WHERE id = ?", BigDecimal.class, orderId);
        assertThat(sum).isNotNull();
        assertThat(sum.compareTo(total)).isZero();
    }

    // -----------------------------------------------------------------
    // 2. Trigger-owned version / updated_at
    // -----------------------------------------------------------------

    @Test
    @DisplayName("orders_touch owns version and updated_at on every UPDATE")
    void the_trigger_owns_version_and_updated_at() {
        long userId = insertUser("Morgan Modern", "morgan@example.com");
        long orderId = orders.insertOrder(userId, new BigDecimal("10.00"), "PENDING");

        Map<String, Object> before = jdbc.queryForMap(
                "SELECT version, updated_at FROM orders WHERE id = ?", orderId);
        assertThat(((Number) before.get("version")).intValue()).isEqualTo(1);

        // A plain UPDATE that never mentions version or updated_at.
        jdbc.update("UPDATE orders SET status = 'PROCESSING' WHERE id = ?", orderId);

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT version, updated_at FROM orders WHERE id = ?", orderId);
        assertThat(((Number) after.get("version")).intValue())
                .as("the trigger, not the caller, must bump version")
                .isEqualTo(2);
        // queryForMap hands back java.sql.Timestamp, not OffsetDateTime.
        assertThat(after.get("updated_at")).isInstanceOf(java.sql.Timestamp.class);
        assertThat(((java.sql.Timestamp) after.get("updated_at")).toInstant())
                .isAfterOrEqualTo(((java.sql.Timestamp) before.get("updated_at")).toInstant());

        // Monotonic across repeated updates, which is what Elasticsearch
        // stale-write protection depends on.
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE orders SET status = 'SHIPPED' WHERE id = ?", orderId);
        }
        assertThat(jdbc.queryForObject(
                "SELECT version FROM orders WHERE id = ?", Integer.class, orderId))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("re-applying the schema is safe and the trigger is not duplicated")
    void the_schema_is_idempotent() {
        long userId = insertUser("Casey Co", "casey@example.com");
        long orderId = orders.insertOrder(userId, new BigDecimal("10.00"), "PENDING");

        schemaLoader.applyAgain();

        Integer triggers = jdbc.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'orders_touch_trg' AND NOT tgisinternal",
                Integer.class);
        assertThat(triggers).as("DROP TRIGGER IF EXISTS must prevent duplicates").isEqualTo(1);

        jdbc.update("UPDATE orders SET status = 'PROCESSING' WHERE id = ?", orderId);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM orders WHERE id = ?", Integer.class, orderId))
                .as("version must advance by exactly one after re-applying the schema")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the database rejects invalid statuses and quantities")
    void the_database_enforces_the_domain_constraints() {
        long userId = insertUser("Dana Data", "dana@example.com");
        long orderId = orders.insertOrder(userId, new BigDecimal("10.00"), "PENDING");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE orders SET status = 'DELIVERED' WHERE id = ?", orderId))
                .hasMessageContaining("orders_status_check");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_items (order_id, product_id, title, quantity, unit_price)"
                        + " VALUES (?, 'p', 't', 0, 1.00)", orderId))
                .hasMessageContaining("order_items_quantity_check");
    }
}