package com.ecommerce.ordermanagement;

import com.ecommerce.ordermanagement.model.SearchDtos.SearchRequest;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchResponse;
import com.ecommerce.ordermanagement.repository.SearchRepository;
import com.ecommerce.ordermanagement.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the admin order-search screen (Screen 3).
 *
 * <p>The key case is money stored in Elasticsearch as a JSON <b>string</b>. Parsing
 * it through {@code String.valueOf(JsonNode)} yields the quoted form
 * ({@code "29.99"}) and makes {@code new BigDecimal(..)} throw, which turned every
 * search into a 500.</p>
 */
class SearchServiceTest {

    private static final String ES_RESPONSE = """
            {
              "hits": {
                "total": { "value": 2, "relation": "eq" },
                "hits": [
                  { "_id": "41", "_source": {
                      "order_id": 41,
                      "order_date": "2026-08-01T00:00:00Z",
                      "status": "SHIPPED",
                      "total_amount": "29.99",
                      "updated_at": "2026-10-04T10:13:43Z",
                      "version": 1,
                      "customer": { "id": 3, "name": "Wendy Wireless",
                                    "email": "wendy.wireless@example.com" },
                      "items": [ { "product_id": "abc", "title": "Wireless Presenter",
                                   "quantity": 1, "unit_price": "29.99" } ]
                  } },
                  { "_id": "40", "_source": {
                      "order_id": 40,
                      "order_date": "2026-08-02T00:00:00Z",
                      "status": "PENDING",
                      "total_amount": "100.32",
                      "updated_at": "2026-10-04T10:13:43Z",
                      "version": 2,
                      "customer": { "id": 1, "name": "John Doe",
                                    "email": "john.doe@example.com" },
                      "items": [ { "product_id": "def", "title": "Desk Lamp LED",
                                   "quantity": 2, "unit_price": "50.16" } ]
                  } }
                ]
              },
              "aggregations": {
                "orders_revenue": { "value": 130.31 },
                "orders_by_status": { "buckets": [
                    { "key": "PENDING", "doc_count": 1 },
                    { "key": "SHIPPED", "doc_count": 1 } ] }
              }
            }
            """;

    private static SearchService serviceReturning(String raw) {
        SearchRepository repo = mock(SearchRepository.class);
        when(repo.searchRaw(anyString())).thenReturn(raw);
        return new SearchService(repo, new ObjectMapper());
    }

    private static SearchRequest request() {
        return new SearchRequest(null, null, null, null, null, null, 1, 10);
    }

    @Test
    void parses_hits_aggregations_and_facets_from_the_raw_es_response() {
        SearchResponse response = serviceReturning(ES_RESPONSE).searchOrders(request());

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.hits()).hasSize(2);
        assertThat(response.hits().get(0).order_id()).isEqualTo(41);
        assertThat(response.hits().get(0).customer().name()).isEqualTo("Wendy Wireless");
        assertThat(response.status_facets())
                .containsEntry("PENDING", 1L)
                .containsEntry("SHIPPED", 1L);
    }

    /** Money in _source is a JSON string: it must parse, not blow up. */
    @Test
    void parses_string_money_and_re_quantizes_aggregation_revenue() {
        SearchResponse response = serviceReturning(ES_RESPONSE).searchOrders(request());

        assertThat(response.hits().get(0).total_amount()).isEqualByComparingTo("29.99");
        assertThat(response.hits().get(0).items().get(0).unit_price())
                .isEqualByComparingTo("29.99");
        assertThat(response.hits().get(1).total_amount()).isEqualByComparingTo("100.32");
        assertThat(response.revenue()).isEqualByComparingTo("130.31");
    }

    /** The response is serialized back with money as exact two-place strings. */
    @Test
    void serializes_search_money_back_as_two_place_strings() throws Exception {
        SearchResponse response = serviceReturning(ES_RESPONSE).searchOrders(request());
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"total_amount\":\"29.99\"")
                .contains("\"unit_price\":\"29.99\"")
                .contains("\"revenue\":\"130.31\"");
    }

    @Test
    void empty_aggregation_revenue_is_reported_as_zero() {
        String noAggs = "{ \"hits\": { \"total\": { \"value\": 0 }, \"hits\": [] } }";
        SearchResponse response = serviceReturning(noAggs).searchOrders(request());

        assertThat(response.total()).isZero();
        assertThat(response.hits()).isEmpty();
        assertThat(response.revenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.status_facets()).isEmpty();
    }

    /**
     * The shipped mapping must declare aggregation-safe field types. A
     * dynamically-created index maps these as text, and every aggregation then
     * fails with "fielddata is disabled".
     */
    @Test
    void shipped_index_mapping_declares_aggregation_safe_field_types() throws Exception {
        var shipped = new ObjectMapper().readTree(
                getClass().getClassLoader().getResourceAsStream("search/orders_mapping.json"));
        var props = shipped.at("/mappings/properties");

        assertThat(props.at("/status/type").asText()).isEqualTo("keyword");
        assertThat(props.at("/total_amount/type").asText()).isEqualTo("scaled_float");
        assertThat(props.at("/total_amount/scaling_factor").asInt()).isEqualTo(100);
        assertThat(props.at("/items/properties/unit_price/type").asText())
                .isEqualTo("scaled_float");
        assertThat(props.at("/items/properties/product_id/type").asText()).isEqualTo("keyword");
    }
}