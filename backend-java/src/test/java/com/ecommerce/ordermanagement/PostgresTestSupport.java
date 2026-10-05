package com.ecommerce.ordermanagement;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points the integration tests at a real PostgreSQL server.
 *
 * <p>These tests exercise behaviour that <em>is</em> database behaviour: the
 * {@code orders_touch} trigger, the {@code order_items} snapshot-immutability
 * trigger, CHECK and foreign-key constraints, and the outbox compare-and-set
 * settlement. None of that is observable through a mock, so the tests talk to an
 * actual server.
 *
 * <p>The connection defaults to the {@code ecommerce-postgres} service from
 * {@code docker-compose.yml} on its published host port, using a dedicated
 * {@code ecommerce_it} database so the application's own data is never touched.
 * Override with {@code IT_PG_JDBC_URL}, {@code IT_PG_USER} and
 * {@code IT_PG_PASSWORD}, and set {@code IT_PG_ENABLED=false} to skip these tests
 * where no server is available.
 *
 * <p>Testcontainers was the first choice here, but the Docker socket is not
 * reachable from this WSL environment, so the tests bind to the already-running
 * service instead. The guarantees under test are unaffected.
 */
final class PostgresTestSupport {

    private PostgresTestSupport() {
    }

    static final String JDBC_URL =
            env("IT_PG_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ecommerce_it");
    static final String USER = env("IT_PG_USER", "ecommerce");
    static final String PASSWORD = env("IT_PG_PASSWORD", "ecommerce");

    private static String authority() {
        String rest = JDBC_URL.replaceFirst("^jdbc:postgresql://", "");
        return rest.substring(0, rest.indexOf('/'));
    }

    private static String database() {
        return JDBC_URL.substring(JDBC_URL.lastIndexOf('/') + 1);
    }

    static boolean enabled() {
        return !"false".equalsIgnoreCase(env("IT_PG_ENABLED", "true"));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Feeds the connection details to Spring Boot before the context is built,
     * so the production repositories resolve against the real server.
     */
    static void registerProperties(DynamicPropertyRegistry registry) {
        // DataSourceConfig builds the DataSource from `app.pg-dsn` (the same
        // PG_DSN shape the former asyncpg pool used), so that is the property
        // that must be overridden. Setting spring.datasource.* alone would be
        // ignored.
        registry.add("app.pg-dsn", () ->
                "postgresql://" + USER + ":" + PASSWORD + "@" + authority() + "/" + database());
        // MongoDB and RabbitMQ are available in the running stack and are left
        // enabled because the production beans (MongoTemplate for the catalog,
        // the AMQP listener) are part of the context. Only Elasticsearch is
        // pointed at an unroutable address: nothing in these tests indexes, and
        // the ES client connects lazily.
        registry.add("spring.elasticsearch.uris", () -> "http://127.0.0.1:1");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("app.seed.run", () -> "false");
    }
}