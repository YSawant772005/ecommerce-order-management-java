package com.ecommerce.ordermanagement.service;

import com.ecommerce.ordermanagement.model.OrderDtos.OrderItemOut;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchHit;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchRequest;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchResponse;
import com.ecommerce.ordermanagement.model.User;
import com.ecommerce.ordermanagement.repository.SearchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen 3 orchestration. Elasticsearch only — no PostgreSQL or MongoDB
 * repository appears here.
 *
 * <p>Aggregations are siblings of {@code size}: {@code revenue} and
 * {@code status_facets} always cover the whole filtered result set, never just
 * the returned page, and are never summed from hits.</p>
 */
@Service
public class SearchService {

    private final SearchRepository searchRepository;
    private final ObjectMapper mapper;

    public SearchService(SearchRepository searchRepository, ObjectMapper mapper) {
        this.searchRepository = searchRepository;
        this.mapper = mapper;
    }

    /**
     * Elasticsearch money -> exact two-place {@link BigDecimal}.
     *
     * <p>{@code _source.total_amount} and {@code items[].unit_price} are stored as
     * JSON <b>strings</b> ("29.99"), so the node is a {@code TextNode}. Its
     * {@code toString()} is the JSON-quoted form ({@code "29.99"}), which
     * {@code new BigDecimal(...)} rejects; {@code asText()} gives the bare value.
     * Aggregation results (revenue) arrive as a numeric node, which
     * {@code asText()} also renders correctly.</p>
     */
    private static BigDecimal money(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    }

    private static String moneyString(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public SearchResponse searchOrders(SearchRequest req) {
        Map<String, Object> body = buildBody(req);
        String raw = searchRepository.searchRaw(write(body));
        return parse(raw, req);
    }

    private String write(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception exc) {
            throw new IllegalStateException("could not serialize search request", exc);
        }
    }

    private Map<String, Object> buildBody(SearchRequest req) {
        List<Object> must = new ArrayList<>();
        if (req.q() != null && !req.q().isBlank()) {
            must.add(Map.of("multi_match", Map.of(
                    "query", req.q(),
                    "fields", List.of(
                            "customer.name^2",
                            "items.title.prefix",
                            "items.title",
                            "customer.email"))));
        } else {
            must.add(Map.of("match_all", Map.of()));
        }

        List<Object> filters = new ArrayList<>();
        if (!req.statuses().isEmpty()) {
            filters.add(Map.of("terms", Map.of("status", req.statuses())));
        }
        if (req.date_from() != null || req.date_to() != null) {
            Map<String, Object> bounds = new LinkedHashMap<>();
            if (req.date_from() != null) {
                bounds.put("gte", req.date_from().toString());
            }
            if (req.date_to() != null) {
                bounds.put("lte", req.date_to().toString());
            }
            filters.add(Map.of("range", Map.of("order_date", bounds)));
        }
        if (req.price_min() != null || req.price_max() != null) {
            Map<String, Object> bounds = new LinkedHashMap<>();
            if (req.price_min() != null) {
                bounds.put("gte", moneyString(req.price_min()));
            }
            if (req.price_max() != null) {
                bounds.put("lte", moneyString(req.price_max()));
            }
            filters.add(Map.of("range", Map.of("total_amount", bounds)));
        }

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", must);
        bool.put("filter", filters);

        Map<String, Object> aggs = new LinkedHashMap<>();
        aggs.put("orders_revenue", Map.of("sum", Map.of("field", "total_amount")));
        aggs.put("orders_by_status", Map.of("terms", Map.of("field", "status")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of("bool", bool));
        body.put("aggs", aggs);
        body.put("from", (req.page() - 1) * req.size());
        body.put("size", req.size());
        body.put("sort", List.of(Map.of("order_date", Map.of("order", "desc"))));
        return body;
    }

    private SearchResponse parse(String raw, SearchRequest req) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception exc) {
            throw new IllegalStateException("could not parse search response", exc);
        }

        JsonNode hitsNode = root.path("hits");
        long total = hitsNode.path("total").path("value").asLong();

        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hit : hitsNode.path("hits")) {
            JsonNode source = hit.path("_source");
            JsonNode customer = source.path("customer");
            List<OrderItemOut> items = new ArrayList<>();
            for (JsonNode item : source.path("items")) {
                items.add(new OrderItemOut(
                        item.path("product_id").asText(),
                        item.path("title").asText(),
                        item.path("quantity").asInt(),
                        money(item.path("unit_price"))));
            }
            hits.add(new SearchHit(
                    source.path("order_id").asLong(),
                    source.path("status").asText(),
                    money(source.path("total_amount")),
                    parseDate(source.path("order_date").asText()),
                    parseDate(source.path("updated_at").asText()),
                    source.path("version").asInt(),
                    new User(
                            customer.path("id").asLong(),
                            customer.path("name").asText(),
                            customer.path("email").asText(),
                            null),
                    items));
        }

        JsonNode aggregations = root.path("aggregations");
        JsonNode revenue = aggregations.path("orders_revenue").path("value");

        Map<String, Long> facets = new LinkedHashMap<>();
        for (JsonNode bucket : aggregations.path("orders_by_status").path("buckets")) {
            facets.put(bucket.path("key").asText(), bucket.path("doc_count").asLong());
        }

        return new SearchResponse(total, req.page(), req.size(), money(revenue), facets, hits);
    }

    private static OffsetDateTime parseDate(String value) {
        return value == null || value.isEmpty() ? null : OffsetDateTime.parse(value);
    }
}