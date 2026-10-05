package com.ecommerce.ordermanagement.service;

import com.ecommerce.ordermanagement.config.AppProperties;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderCreate;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderItemIn;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderOut;
import com.ecommerce.ordermanagement.model.ProductDtos.Product;
import com.ecommerce.ordermanagement.repository.OrderRepository;
import com.ecommerce.ordermanagement.repository.OrderRepository.Line;
import com.ecommerce.ordermanagement.repository.OrderRepository.OrderRow;
import com.ecommerce.ordermanagement.repository.ProductRepository;
import com.ecommerce.ordermanagement.web.ApiException;
import com.ecommerce.ordermanagement.worker.SyncDispatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Order placement and the guarded status change.
 *
 * <p>Both operations follow the same three-step shape, and the order is the
 * design: (1) validate against the catalog first — before any PostgreSQL write,
 * so a bad cart leaves no trace; (2) one transaction commits {@code orders},
 * {@code order_items} and the {@code outbox} event together; (3) dispatch to the
 * queue only after commit, because a worker that started earlier would project
 * an order that does not exist.</p>
 */
@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final SyncDispatcher dispatcher;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository,
                        SyncDispatcher dispatcher, TransactionTemplate tx, AppProperties props) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.dispatcher = dispatcher;
        this.tx = tx;
        this.props = props;
    }

    public OrderOut placeOrder(OrderCreate req) {
        List<Line> lines = resolveLines(req);
        BigDecimal total = lines.stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        long[] ids = tx.execute(status -> {
            if (!orderRepository.userExists(req.user_id())) {
                throw ApiException.notFound("user " + req.user_id() + " not found");
            }
            long orderId = orderRepository.insertOrder(req.user_id(), total, "PENDING");
            orderRepository.insertOrderItems(orderId, lines);
            long outboxId = orderRepository.insertOutboxEvent(orderId, OrderRepository.ORDER_CREATED);
            return new long[]{orderId, outboxId};
        });

        // After COMMIT, never before.
        dispatcher.dispatch(ids[0], ids[1]);

        return new OrderOut(ids[0], total, "PENDING", props.getOrderSyncPlacementStatus());
    }

    public OrderOut updateStatus(long orderId, String status, int expectedVersion) {
        Object[] committed = tx.execute(txStatus -> {
            var result = orderRepository.updateStatusGuarded(orderId, status, expectedVersion);
            if (!result.exists()) {
                throw ApiException.notFound("order " + orderId + " not found");
            }
            if (result.updated() == 0) {
                throw ApiException.conflict("order " + orderId + " has moved on: expected_version="
                        + expectedVersion + " is stale. Re-read the order and retry.");
            }
            OrderRow row = orderRepository.fetchOrderRow(orderId);
            long outboxId = orderRepository.insertOutboxEvent(orderId, OrderRepository.ORDER_STATUS_CHANGED);
            return new Object[]{row, outboxId};
        });

        dispatcher.dispatch(orderId, (Long) committed[1]);
        OrderRow row = (OrderRow) committed[0];
        return new OrderOut(row.id(), row.totalAmount(), row.status(), props.getOrderSyncPlacementStatus());
    }

    /**
     * Read the catalog and price the cart. One {@code $in} query for the whole
     * cart; duplicate lines merged so one product yields one {@code order_items}
     * row. Raises 409 before any PostgreSQL write.
     */
    private List<Line> resolveLines(OrderCreate req) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (OrderItemIn item : req.items()) {
            wanted.merge(item.product_id(), item.quantity(), Integer::sum);
        }
        Map<String, Product> products = productRepository.getManyByIds(new ArrayList<>(wanted.keySet()));

        List<String> missing = new ArrayList<>();
        List<String> inactive = new ArrayList<>();
        for (String pid : wanted.keySet()) {
            Product product = products.get(pid);
            if (product == null) {
                missing.add(pid);
            } else if (!product.active()) {
                inactive.add(product.title());
            }
        }
        if (!missing.isEmpty() || !inactive.isEmpty()) {
            List<String> detail = new ArrayList<>();
            if (!missing.isEmpty()) {
                detail.add("not found: " + String.join(", ", missing));
            }
            if (!inactive.isEmpty()) {
                detail.add("not available: " + String.join(", ", inactive));
            }
            throw ApiException.conflict(String.join("; ", detail));
        }

        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            Product product = products.get(entry.getKey());
            lines.add(new Line(entry.getKey(), product.title(), entry.getValue(), product.price()));
        }
        return lines;
    }
}
