package com.ecommerce.ordermanagement.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * PostgreSQL access for orders and order items.
 *
 * <p>The caller owns the transaction, exactly as the former {@code orders_repo}
 * required a connection rather than reaching for a pool: the service opens one
 * transaction that commits {@code orders}, {@code order_items} and the
 * {@code outbox} event together, or none of them.</p>
 */
@Repository
public class OrderRepository {

    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    private final JdbcTemplate jdbc;
    private final OutboxRepository outboxRepository;

    public OrderRepository(JdbcTemplate jdbc, OutboxRepository outboxRepository) {
        this.jdbc = jdbc;
        this.outboxRepository = outboxRepository;
    }

    /** One checkout line, as it will be snapshotted into {@code order_items}. */
    public record Line(String productId, String title, int quantity, BigDecimal unitPrice) {
    }

    public record OrderRow(
            long id,
            long userId,
            OffsetDateTime orderDate,
            String status,
            BigDecimal totalAmount,
            OffsetDateTime updatedAt,
            int version,
            String customerName,
            String customerEmail) {
    }

    public record ItemRow(String productId, String title, int quantity, BigDecimal unitPrice) {
    }

    private static final RowMapper<OrderRow> ORDER_ROW = (rs, n) -> new OrderRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getObject("order_date", OffsetDateTime.class),
            rs.getString("status"),
            rs.getBigDecimal("total_amount"),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getInt("version"),
            rs.getString("customer_name"),
            rs.getString("customer_email"));

    private static final RowMapper<ItemRow> ITEM_ROW = (rs, n) -> new ItemRow(
            rs.getString("product_id"),
            rs.getString("title"),
            rs.getInt("quantity"),
            rs.getBigDecimal("unit_price"));

    /** Insert one order and return its id. {@code version} starts at 1. */
    public long insertOrder(long userId, BigDecimal totalAmount, String status) {
        Long id = jdbc.queryForObject(
                "INSERT INTO orders (user_id, status, total_amount) VALUES (?, ?, ?) RETURNING id",
                Long.class, userId, status, totalAmount);
        return id == null ? 0L : id;
    }

    /** Insert the checkout snapshots: {@code (product_id, title, quantity, unit_price)}. */
    public void insertOrderItems(long orderId, List<Line> lines) {
        jdbc.batchUpdate(
                "INSERT INTO order_items (order_id, product_id, title, quantity, unit_price)"
                        + " VALUES (?, ?, ?, ?, ?)",
                lines,
                lines.size(),
                (ps, line) -> {
                    ps.setLong(1, orderId);
                    ps.setString(2, line.productId());
                    ps.setString(3, line.title());
                    ps.setInt(4, line.quantity());
                    ps.setBigDecimal(5, line.unitPrice());
                });
    }

    /** Record the intent to index, inside the caller's transaction. Returns the event id. */
    public long insertOutboxEvent(long orderId, String eventType) {
        return outboxRepository.enqueue(orderId, eventType);
    }

    public record StatusUpdateResult(boolean exists, int updated) {
    }

    /**
     * Update status only if the caller is not stale. {@code updated == 0} with
     * {@code exists} means the optimistic lock lost (the service turns that into
     * a 409); the {@code orders_touch} trigger bumps {@code version}/{@code updated_at}.
     */
    public StatusUpdateResult updateStatusGuarded(long orderId, String status, int expectedVersion) {
        Integer exists = jdbc.query(
                "SELECT 1 FROM orders WHERE id = ?",
                rs -> rs.next() ? 1 : null, orderId);
        if (exists == null) {
            return new StatusUpdateResult(false, 0);
        }
        int updated = jdbc.update(
                "UPDATE orders SET status = ? WHERE id = ? AND version = ?",
                status, orderId, expectedVersion);
        return new StatusUpdateResult(true, updated);
    }

    public OrderRow fetchOrderRow(long orderId) {
        var rows = jdbc.query(
                "SELECT o.id, o.user_id, o.order_date, o.status, o.total_amount, o.updated_at, o.version,"
                        + " u.name AS customer_name, u.email AS customer_email"
                        + " FROM orders o JOIN users u ON u.id = o.user_id WHERE o.id = ?",
                ORDER_ROW, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ItemRow> fetchOrderItems(long orderId) {
        return jdbc.query(
                "SELECT product_id, title, quantity, unit_price FROM order_items"
                        + " WHERE order_id = ? ORDER BY id",
                ITEM_ROW, orderId);
    }

    public boolean userExists(long userId) {
        Integer one = jdbc.query("SELECT 1 FROM users WHERE id = ?",
                rs -> rs.next() ? 1 : null, userId);
        return one != null;
    }

    /** Every order id, oldest first. The reindex path walks this list. */
    public List<Long> listOrderIds() {
        return jdbc.queryForList("SELECT id FROM orders ORDER BY id", Long.class);
    }
}
