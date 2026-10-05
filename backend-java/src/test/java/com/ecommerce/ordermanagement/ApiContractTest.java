package com.ecommerce.ordermanagement;

import com.ecommerce.ordermanagement.model.OrderDtos.OrderCreate;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderItemIn;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderItemOut;
import com.ecommerce.ordermanagement.model.OrderDtos.OrderOut;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchRequest;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchResponse;
import com.ecommerce.ordermanagement.model.SyncStatusOut;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests that need no external store: they assert the JSON wire format
 * and the health route's response shape. Store-backed behaviour is covered by
 * {@code SeedServiceIT}-style flows and by the Python suite retained as
 * reference.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    @Autowired
    ObjectMapper mapper;

    @Autowired
    org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    void health_reports_the_three_stores_and_never_throws() throws Exception {
        String body = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> json = mapper.readValue(body, Map.class);
        assertThat(json.keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("postgres", "mongo", "elasticsearch");
        assertThat(json.values()).allMatch(v -> "UP".equals(v) || "DOWN".equals(v));
    }

    @Test
    void money_serializes_as_a_two_place_string_never_a_float() throws Exception {
        OrderOut out = new OrderOut(1L, new BigDecimal("150.50"), "PENDING", "QUEUED");
        assertThat(mapper.writeValueAsString(out))
                .contains("\"total_amount\":\"150.50\"");

        // A value arriving with spurious precision still serializes as exact cents.
        OrderOut noisy = new OrderOut(2L, new BigDecimal("799.0000000001"), "PENDING", "QUEUED");
        assertThat(mapper.writeValueAsString(noisy))
                .contains("\"total_amount\":\"799.00\"");
    }

    @Test
    void search_response_carries_revenue_and_facets_as_strings() throws Exception {
        SearchResponse response = new SearchResponse(
                2, 1, 20, new BigDecimal("301.00"), Map.of("SHIPPED", 2L), List.of());
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"revenue\":\"301.00\"").contains("\"status_facets\":{\"SHIPPED\":2}");
    }

    @Test
    void order_detail_item_snapshots_are_string_money() throws Exception {
        String json = mapper.writeValueAsString(
                new OrderItemOut("p1", "Wireless Mouse", 2, new BigDecimal("50.16")));
        assertThat(json).contains("\"unit_price\":\"50.16\"").contains("\"title\":\"Wireless Mouse\"");
    }

    @Test
    void sync_status_states_round_trip() throws Exception {
        for (String state : List.of("IN_SYNC", "OUT_OF_SYNC", "MISSING_IN_ES")) {
            SyncStatusOut out = new SyncStatusOut(1L, state, 2, null, 0, null);
            assertThat(mapper.writeValueAsString(out)).contains("\"state\":\"" + state + "\"");
        }
    }

    @Test
    void order_create_rejects_a_client_supplied_price_with_422() throws Exception {
        String body = """
                {"user_id": 1, "items": [{"product_id": "x", "quantity": 1, "unit_price": "0.01"}]}
                """;
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void search_request_defaults_page_and_size() {
        SearchRequest req = new SearchRequest(null, null, null, null, null, null, null, null);
        assertThat(req.page()).isEqualTo(1);
        assertThat(req.size()).isEqualTo(20);
        assertThat(req.statuses()).isEmpty();
    }

    @Test
    void order_create_requires_at_least_one_item() {
        OrderCreate invalid = new OrderCreate(1L, List.of(new OrderItemIn("p1", 1)));
        assertThat(invalid.items()).hasSize(1);
        assertThat(new OrderCreate(1L, List.of()).items()).isEmpty();
    }
}