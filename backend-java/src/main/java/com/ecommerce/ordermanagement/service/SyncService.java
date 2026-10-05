package com.ecommerce.ordermanagement.service;

import com.ecommerce.ordermanagement.model.SyncStatusOut;
import com.ecommerce.ordermanagement.repository.OrderRepository;
import com.ecommerce.ordermanagement.repository.OrderRepository.ItemRow;
import com.ecommerce.ordermanagement.repository.OrderRepository.OrderRow;
import com.ecommerce.ordermanagement.repository.OutboxRepository;
import com.ecommerce.ordermanagement.repository.OutboxRepository.PendingEvent;
import com.ecommerce.ordermanagement.repository.SearchRepository;
import com.ecommerce.ordermanagement.repository.SearchRepository.EsIndexException;
import com.ecommerce.ordermanagement.repository.SearchRepository.EsVersionConflict;
import com.ecommerce.ordermanagement.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical projection, and the vocabulary for reporting on it.
 *
 * <p>{@link #buildOrderDocument(long)} is the only code that produces an
 * Elasticsearch order document, and it is a pure function of <b>committed
 * PostgreSQL rows</b> — the order, its customer and the {@code order_items}
 * snapshots. It never reads MongoDB, which is why a renamed or repriced product
 * cannot rewrite history.</p>
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    public static final String IN_SYNC = "IN_SYNC";
    public static final String OUT_OF_SYNC = "OUT_OF_SYNC";
    public static final String MISSING_IN_ES = "MISSING_IN_ES";

    private static final DateTimeFormatter ISO = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final SearchRepository searchRepository;
    private final ObjectMapper mapper;

    public SyncService(OrderRepository orderRepository, OutboxRepository outboxRepository,
                       SearchRepository searchRepository, ObjectMapper mapper) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.searchRepository = searchRepository;
        this.mapper = mapper;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : ISO.format(value.toInstant());
    }

    /** Project one committed order into the Elasticsearch document shape. */
    public Map<String, Object> buildOrderDocument(long orderId) {
        OrderRow row = orderRepository.fetchOrderRow(orderId);
        if (row == null) {
            throw ApiException.notFound("order " + orderId + " not found in PostgreSQL");
        }
        List<ItemRow> items = orderRepository.fetchOrderItems(orderId);

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", row.userId());
        customer.put("name", row.customerName());
        customer.put("email", row.customerEmail());

        List<Map<String, Object>> itemDocs = new ArrayList<>();
        for (ItemRow item : items) {
            Map<String, Object> itemDoc = new LinkedHashMap<>();
            itemDoc.put("product_id", item.productId());
            itemDoc.put("title", item.title());
            itemDoc.put("quantity", item.quantity());
            itemDoc.put("unit_price", money(item.unitPrice()));
            itemDocs.add(itemDoc);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("order_id", row.id());
        doc.put("order_date", iso(row.orderDate()));
        doc.put("status", row.status());
        doc.put("total_amount", money(row.totalAmount()));
        doc.put("updated_at", iso(row.updatedAt()));
        doc.put("version", row.version());
        doc.put("customer", customer);
        doc.put("items", itemDocs);
        return doc;
    }

    /**
     * Index one order. {@code outboxId} names the exact event being settled;
     * {@code null} means index-and-settle-nothing (reindex / manual path).
     *
     * <p>A stale version is terminal ({@code markSuperseded}, never retried); an
     * outage stays retryable ({@code markFailed}, re-raised so the drain retries).</p>
     */
    public void indexOrder(long orderId, Long outboxId) {
        Map<String, Object> doc = buildOrderDocument(orderId);
        int version = (int) doc.get("version");
        String json;
        try {
            json = mapper.writeValueAsString(doc);
        } catch (Exception exc) {
            throw new EsIndexException("serialize order " + orderId + " failed", exc);
        }

        try {
            searchRepository.indexOrderDocument(orderId, json, version);
        } catch (EsVersionConflict exc) {
            if (outboxId != null) {
                outboxRepository.markSuperseded(outboxId, "superseded: " + exc.getMessage());
            }
            return;
        } catch (EsIndexException exc) {
            if (outboxId != null) {
                outboxRepository.markFailed(outboxId, exc.getMessage());
            }
            throw exc;
        }
        if (outboxId != null) {
            outboxRepository.markProcessed(outboxId);
        }
    }

    /** Re-dispatch every pending event. One row's failure never stops the rest. */
    public Map<String, Object> drainOutbox(int limit) {
        List<PendingEvent> claimed = outboxRepository.claimUnprocessed(limit);
        int settled = 0;
        for (PendingEvent event : claimed) {
            try {
                indexOrder(event.aggregateId(), event.id());
            } catch (Exception exc) {
                continue;
            }
            settled++;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("claimed", claimed.size());
        report.put("settled", settled);
        return report;
    }

    /** Delete, recreate and fully repopulate the orders index from PostgreSQL. */
    public Map<String, Object> reindex() {
        searchRepository.deleteIndex();
        searchRepository.ensureOrdersIndex();
        List<Long> orderIds = orderRepository.listOrderIds();
        for (Long orderId : orderIds) {
            indexOrder(orderId, null);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("indexed", orderIds.size());
        return report;
    }

    /** The badge: compare the committed PostgreSQL version against Elasticsearch. */
    public SyncStatusOut syncStatus(long orderId) {
        OrderRow row = orderRepository.fetchOrderRow(orderId);
        if (row == null) {
            throw ApiException.notFound("order " + orderId + " not found");
        }
        long pending = outboxRepository.countPending(orderId);
        String lastError = outboxRepository.lastError(orderId);
        Integer esVersion = searchRepository.getStoredVersion(orderId);

        String state;
        if (esVersion == null) {
            state = MISSING_IN_ES;
        } else if (esVersion == row.version()) {
            state = IN_SYNC;
        } else {
            state = OUT_OF_SYNC;
        }
        log.debug("sync status order {}: {} (pg={}, es={})", orderId, state, row.version(), esVersion);
        return new SyncStatusOut(orderId, state, row.version(), esVersion, pending, lastError);
    }
}
