package com.ecommerce.ordermanagement.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Product catalog models — the MongoDB document, as the API exposes it.
 *
 * <p>{@code price} rides as an exact two-place string like every other monetary
 * value. {@code attributes} is a free-form object and {@code variants} an array
 * of sub-documents, which is the whole reason the catalog lives in MongoDB.
 * The identifier is exposed as {@code _id} (the frontend reads {@code _id}).</p>
 */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record Variant(
            String sku,
            String color,
            Integer stock,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal price) {
    }

    /** A catalog document. {@code _id} is the MongoDB ObjectId as a string. */
    public record Product(
            @JsonProperty("_id") String id,
            String sku,
            String title,
            String description,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal price,
            String category,
            List<String> tags,
            Map<String, Object> attributes,
            List<Variant> variants,
            boolean active,
            OffsetDateTime updated_at) {
    }

    /** Screen 5 create. Shape rules (>=1 variant, >=1 attribute) enforced in the service. */
    public record ProductCreate(
            String sku,
            String title,
            String description,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal price,
            String category,
            List<String> tags,
            Map<String, Object> attributes,
            List<Variant> variants,
            Boolean active) {

        public ProductCreate {
            if (description == null) {
                description = "";
            }
            if (tags == null) {
                tags = List.of();
            }
            if (attributes == null) {
                attributes = Map.of();
            }
            if (variants == null) {
                variants = List.of();
            }
            if (active == null) {
                active = Boolean.TRUE;
            }
        }
    }

    /**
     * One page of catalog results plus everything a client needs to page onward.
     * {@code items} carries the existing product documents unchanged; only the
     * envelope around them is new, so the product structure is untouched.
     */
    public record ProductPageResponse(
            @JsonProperty("items") List<Product> items,
            int page,
            int size,
            long totalItems,
            int totalPages,
            boolean hasNext) {
    }

    /** Screen 5 edit. Every field optional; only non-null fields are written. */
    public record ProductUpdate(
            String sku,
            String title,
            String description,
            @JsonSerialize(using = MoneyCodec.MoneySerializer.class)
            @JsonDeserialize(using = MoneyCodec.MoneyDeserializer.class) BigDecimal price,
            String category,
            List<String> tags,
            Map<String, Object> attributes,
            List<Variant> variants,
            Boolean active) {
    }
}
