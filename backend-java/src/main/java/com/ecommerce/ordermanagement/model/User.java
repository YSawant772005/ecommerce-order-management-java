package com.ecommerce.ordermanagement.model;

import java.time.OffsetDateTime;

/** A single customer. Lives in PostgreSQL — the catalog is MongoDB's alone. */
public record User(long id, String name, String email, OffsetDateTime created_at) {
}
