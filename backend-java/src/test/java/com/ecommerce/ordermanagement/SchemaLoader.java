package com.ecommerce.ordermanagement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies the Java-owned schema to a PostgreSQL database.
 *
 * <p>The schema lives at {@code db/001_schema.sql} in this project's own
 * resources, so applying it here exercises the same file the application
 * container applies. The Python reference tree is not involved.
 *
 * <p>The DDL contains {@code $$ ... $$} function bodies, so it cannot simply be
 * split on {@code ';'}. It is executed as a single multi-statement script
 * through the PostgreSQL JDBC driver, which handles dollar-quoting correctly.
 */
@Component
public class SchemaLoader {

    public static final String SCHEMA_RESOURCE = "db/001_schema.sql";

    private final DataSource dataSource;

    public SchemaLoader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The raw DDL, as shipped in the jar. */
    public static String readSchemaSql() {
        try (InputStream in = SchemaLoader.class.getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("schema not found on classpath: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new IllegalStateException("could not read " + SCHEMA_RESOURCE, exc);
        }
    }

    /**
     * Applies the schema. It is idempotent by construction, so calling this on
     * every test run is both safe and itself part of what is under test.
     */
    public void apply() {
        new JdbcTemplate(dataSource).execute(readSchemaSql());
    }

    /** Applies the schema a second time to prove re-application is safe. */
    public void applyAgain() {
        apply();
    }

    /** Empties the data tables without dropping the schema. */
    public void truncateAll() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> statements = new ArrayList<>(List.of(
                "TRUNCATE order_items RESTART IDENTITY CASCADE",
                "TRUNCATE outbox RESTART IDENTITY CASCADE",
                "TRUNCATE orders RESTART IDENTITY CASCADE",
                "TRUNCATE users RESTART IDENTITY CASCADE"));
        statements.forEach(jdbc::execute);
    }
}