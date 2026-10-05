package com.ecommerce.ordermanagement.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Order request and response models.
 *
 * <p>{@code OrderCreate} forbids extra keys (enforced globally by Jackson plus
 * the 422 advice): the client sends only {@code product_id} and
 * {@code quantity}; the server derives {@code title} and {@code unit_price}
 * from MongoDB.</p>
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /** One cart line as the client may describe it — nothing more. */
    public record OrderItemIn(@NotBlank String product_id, @Min(1) int quantity) {
    }

    public record OrderCreate(@Min(1) long user_id, @NotEmpty @Valid List<OrderItemIn> items) {
    }

    /** A historical line. {@code title} and {@code unit_price} are snapshots. */
    public record OrderItemOut(
            String product_id,
            String title,
            int quantity,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal unit_price) {
    }

    /** The 201 response. Says the event was queued, never that it was indexed. */
    public record OrderOut(
            long order_id,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal total_amount,
            String status,
            String sync_status) {
    }

    /** Read from PostgreSQL only. */
    public record OrderDetail(
            long order_id,
            long user_id,
            String user_name,
            String user_email,
            OffsetDateTime order_date,
            OffsetDateTime updated_at,
            String status,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal total_amount,
            int version,
            List<OrderItemOut> items) {
    }

    /** Guarded status change: the caller states the version it believes is current. */
    public record StatusUpdate(
            @Pattern(regexp = "PENDING|PROCESSING|SHIPPED") String status,
            @Min(1) int expected_version) {
    }
}
