package com.ecommerce.ordermanagement.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin order search request and response. Every field in a hit is read from
 * <b>Elasticsearch only</b>; the projection was built from committed
 * PostgreSQL rows at index time.
 */
public final class SearchDtos {

    private SearchDtos() {
    }

    /** The admin search filter object. {@code POST} because the body is complex. */
    public record SearchRequest(
            String q,
            List<String> statuses,
            OffsetDateTime date_from,
            OffsetDateTime date_to,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal price_min,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal price_max,
            @Min(1) Integer page,
            @Min(1) @Max(100) Integer size) {

        public SearchRequest {
            if (statuses == null) {
                statuses = List.of();
            }
            if (page == null) {
                page = 1;
            }
            if (size == null) {
                size = 20;
            }
        }
    }

    /** One indexed order. Field names match the indexed document exactly. */
    public record SearchHit(
            long order_id,
            String status,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal total_amount,
            OffsetDateTime order_date,
            OffsetDateTime updated_at,
            int version,
            User customer,
            List<OrderDtos.OrderItemOut> items) {
    }

    /**
     * Hits plus aggregations computed over the whole filtered set.
     *
     * <p>{@code totalPages} and {@code hasNext} are added so the admin screen can
     * render a numbered pager. Both are additive: the existing
     * {@code total}/{@code page}/{@code size}/{@code hits} contract is unchanged,
     * and the aggregates still describe the entire filtered set, not the page.</p>
     */
    public record SearchResponse(
            long total,
            int page,
            int size,
            int totalPages,
            boolean hasNext,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal revenue,
            Map<String, Long> status_facets,
            List<SearchHit> hits) {
    }
}
