package com.ecommerce.ordermanagement.seed;

import com.ecommerce.ordermanagement.config.AppProperties;
import com.ecommerce.ordermanagement.model.ProductDtos.Product;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductCreate;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductUpdate;
import com.ecommerce.ordermanagement.model.ProductDtos.Variant;
import com.ecommerce.ordermanagement.repository.OrderRepository.Line;
import com.ecommerce.ordermanagement.repository.ProductRepository;
import com.ecommerce.ordermanagement.repository.SearchRepository;
import com.ecommerce.ordermanagement.seed.SeedPlan.OrderSpec;
import com.ecommerce.ordermanagement.seed.SeedPlan.ProductSeed;
import com.ecommerce.ordermanagement.service.SyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes all three stores from {@link SeedPlan} and then re-reads them to verify
 * 18 invariants. A failure is a hard error, never a warning.
 *
 * <p>The deliberate catalog/history mismatch is applied last and only in
 * MongoDB: the live product is renamed and repriced while every historical row
 * and Elasticsearch document keeps the original snapshot.</p>
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final SearchRepository searchRepository;
    private final SyncService syncService;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public SeedService(JdbcTemplate jdbc, ProductRepository products, SearchRepository searchRepository,
                       SyncService syncService, ObjectMapper mapper, AppProperties props) {
        this.jdbc = jdbc;
        this.products = products;
        this.searchRepository = searchRepository;
        this.syncService = syncService;
        this.mapper = mapper;
        this.props = props;
    }

    /** Parse the anchor date (a constant, never the current clock). */
    public static OffsetDateTime parseAnchor(String value) {
        return OffsetDateTime.parse(value);
    }

    /** Wipe all three stores, then write the plan. Returns the invariant report. */
    public Map<String, Object> runSeed(long seed, OffsetDateTime anchor, boolean reset) {
        if (reset) {
            jdbc.execute("TRUNCATE order_items, outbox, orders, users RESTART IDENTITY CASCADE");
            products.dropCollection();
            searchRepository.deleteIndex();
        }
        searchRepository.ensureOrdersIndex();

        List<Long> userIds = insertUsers();
        insertCatalog(seed);
        List<Long> orderIds = insertOrders(seed, anchor, userIds);
        indexAll(orderIds);

        // Snapshot-mismatch fixture: MongoDB only, applied after the index is built.
        Product mouse = products.getBySku("WM-001");
        products.update(mouse.id(), new ProductUpdate(
                null, "Wireless Mouse Pro", null, new BigDecimal("79.00"),
                null, null, null, null, null));

        return verify(orderIds.size());
    }

    private List<Long> insertUsers() {
        List<Long> ids = new ArrayList<>();
        for (SeedPlan.UserSeed user : SeedPlan.USERS) {
            ids.add(jdbc.queryForObject(
                    "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id",
                    Long.class, user.name(), user.email()));
        }
        return ids;
    }

    /**
     * The whole catalog: 6 named + 18 fillers (all active), 1 inactive, and the
     * deterministic generated expansion. The hand-written 25 are inserted first so
     * the fixtures orders and tests depend on keep their existing identities.
     */
    private void insertCatalog(long seed) {
        Random rng = new Random(seed);
        List<ProductSeed> specs = new ArrayList<>(SeedPlan.NAMED_PRODUCTS);
        specs.addAll(SeedPlan.FILLERS);
        for (ProductSeed spec : specs) {
            products.create(new ProductCreate(
                    spec.sku(),
                    spec.title(),
                    describe(spec),
                    new BigDecimal(spec.price()),
                    spec.category(),
                    spec.tags(),
                    spec.attributes(),
                    defaultVariants(spec, rng),
                    Boolean.TRUE));
        }
        products.create(new ProductCreate(
                SeedPlan.INACTIVE_PRODUCT.sku(),
                SeedPlan.INACTIVE_PRODUCT.title(),
                describe(SeedPlan.INACTIVE_PRODUCT),
                new BigDecimal(SeedPlan.INACTIVE_PRODUCT.price()),
                SeedPlan.INACTIVE_PRODUCT.category(),
                SeedPlan.INACTIVE_PRODUCT.tags(),
                SeedPlan.INACTIVE_PRODUCT.attributes(),
                List.of(new Variant(SeedPlan.INACTIVE_PRODUCT.sku() + "-V1", "grey", 0, null)),
                Boolean.FALSE));

        insertGeneratedCatalog(seed);
        products.ensureIndexes();
    }

    /**
     * Bulk-insert the generated expansion. Uses the repository's bulk path rather
     * than 3,500 individual inserts, so a fresh seed stays a matter of seconds.
     * SKUs are {@code GEN-00000…}, deterministic and disjoint from the fixtures.
     */
    private void insertGeneratedCatalog(long seed) {
        Random rng = new Random(seed ^ 0x9E37_79B9L);
        List<ProductCreate> batch = new ArrayList<>(SeedPlan.GENERATED_COUNT);
        for (ProductSeed spec : SeedPlan.generatedCatalog(seed)) {
            batch.add(new ProductCreate(
                    spec.sku(),
                    spec.title(),
                    describe(spec),
                    new BigDecimal(spec.price()),
                    spec.category(),
                    spec.tags(),
                    spec.attributes(),
                    List.of(new Variant(spec.sku() + "-V1",
                            String.valueOf(spec.attributes().get("color")),
                            5 + rng.nextInt(46), null)),
                    Boolean.TRUE));
        }
        products.createAll(batch);
        log.info("inserted {} generated products into {}", batch.size(), ProductRepository.COLLECTION);
    }

    /** The two named products that ship with real variant arrays. */
    private static List<Variant> defaultVariants(ProductSeed spec, Random rng) {
        if (spec.sku().equals("MK-002")) {
            return List.of(
                    new Variant("MK-002-BRN", "black", 20, null),
                    new Variant("MK-002-RED", "white", 15, null));
        }
        if (spec.sku().equals("DL-005")) {
            return List.of(
                    new Variant("DL-005-WHT", "white", 30, null),
                    new Variant("DL-005-BLK", "black", 25, null));
        }
        return List.of(new Variant(spec.sku() + "-V1", "black", 5 + rng.nextInt(46), null));
    }

    private static String describe(ProductSeed spec) {
        return spec.title() + " — " + spec.category() + " catalog entry.";
    }

    /**
     * Build each planned line against the <b>live</b> catalog and write the
     * order plus its immutable snapshots. Totals are computed from the lines,
     * never generated.
     */
    private List<Long> insertOrders(long seed, OffsetDateTime anchor, List<Long> userIds) {
        Map<String, Product> byTitle = new LinkedHashMap<>();
        // The order plan only references the 25 hand-written seeds, and the
        // generated expansion now contains thousands of similar titles ("Wireless
        // Mouse Mk II" beside "Wireless Mouse"). Resolve by exact SKU — unique
        // index, no regex — so a neighbouring title can never be picked up.
        List<SeedPlan.ProductSeed> planned = new ArrayList<>(SeedPlan.NAMED_PRODUCTS);
        planned.addAll(SeedPlan.FILLERS);
        for (SeedPlan.ProductSeed spec : planned) {
            Product product = products.getBySku(spec.sku());
            if (product == null) {
                throw new IllegalStateException("catalog is missing planned product " + spec.sku());
            }
            byTitle.put(product.title(), product);
        }

        List<Long> orderIds = new ArrayList<>();
        for (OrderSpec spec : SeedPlan.buildOrderPlan(seed, anchor)) {
            List<Line> lines = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (SeedPlan.Line plannedLine : spec.lines()) {
                String title = SeedPlan.titleFor(plannedLine.productIndex());
                Product product = byTitle.get(title);
                BigDecimal unit = product.price();
                lines.add(new Line(product.id(), title, plannedLine.quantity(), unit));
                total = total.add(unit.multiply(BigDecimal.valueOf(plannedLine.quantity())));
            }
            total = total.setScale(2, RoundingMode.HALF_UP);

            Long orderId = jdbc.queryForObject(
                    "INSERT INTO orders (user_id, order_date, status, total_amount)"
                            + " VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, userIds.get(spec.userIdx()), spec.date(), spec.status(), total);
            jdbc.batchUpdate(
                    "INSERT INTO order_items (order_id, product_id, title, quantity, unit_price)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    lines,
                    lines.size(),
                    (ps, line) -> {
                        ps.setLong(1, orderId);
                        ps.setString(2, line.productId());
                        ps.setString(3, line.title());
                        ps.setInt(4, line.quantity());
                        ps.setBigDecimal(5, line.unitPrice());
                    });
            orderIds.add(orderId);
        }
        return orderIds;
    }

    /** Build each order document through the canonical projection and index it. */
    private void indexAll(List<Long> orderIds) {
        for (Long orderId : orderIds) {
            Map<String, Object> doc = syncService.buildOrderDocument(orderId);
            try {
                searchRepository.indexOrderDocument(
                        orderId, mapper.writeValueAsString(doc), (int) doc.get("version"));
            } catch (Exception exc) {
                throw new IllegalStateException("could not index seeded order " + orderId, exc);
            }
        }
        searchRepository.refresh();
        log.info("indexed {} seeded orders into {}", orderIds.size(), props.getEsOrdersIndex());
    }

    /** Re-read all three stores and assert every invariant; failures throw. */
    public Map<String, Object> verify() {
        return verify(-1);
    }

    private Map<String, Object> verify(int expectedOrders) {
        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();

        long users = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        check(report, failures, "8 users", users == 8, users);

        // Catalog size is now 25 hand-written fixtures + the generated expansion.
        var all = products.list(null, null, null, 0, 1);
        check(report, failures, SeedPlan.EXPECTED_PRODUCTS + " products",
                all.total() == SeedPlan.EXPECTED_PRODUCTS, all.total());

        // The hand-written fixtures must all still exist, with the right active flag.
        // Checked by SKU, not by page scan: the generated catalog now shares the
        // title-sorted listing, so "the first 25" is no longer the fixture set.
        List<SeedPlan.ProductSeed> fixtureSpecs = new ArrayList<>(SeedPlan.NAMED_PRODUCTS);
        fixtureSpecs.addAll(SeedPlan.FILLERS);
        long fixturesPresent = fixtureSpecs.stream()
                .filter(s -> {
                    Product p = products.getBySku(s.sku());
                    return p != null && p.active();
                })
                .count();
        check(report, failures, "25 fixture products still active",
                fixturesPresent == fixtureSpecs.size(), fixturesPresent);
        check(report, failures, "inactive fixture still present",
                products.getBySku(SeedPlan.INACTIVE_PRODUCT.sku()) != null,
                SeedPlan.INACTIVE_PRODUCT.sku());

        check(report, failures, "inactive product hidden from default listing",
                products.list(null, null, Boolean.TRUE, 0, 1).total()
                        == SeedPlan.EXPECTED_PRODUCTS - 1,
                products.list(null, null, Boolean.TRUE, 0, 1).total());

        for (String category : SeedPlan.CATEGORIES) {
            long inCategory = products.list(null, category, Boolean.TRUE, 0, 1).total();
            check(report, failures, "category " + category + " >= 5", inCategory >= 5, inCategory);
        }

        // Price-band and wireless invariants run over the 25 fixtures, which is
        // what they always meant. Resolving by SKU keeps them independent of the
        // generated catalog, which now shares the title-sorted listing.
        List<Product> fixtures = fixtureSpecs.stream()
                .map(s -> products.getBySku(s.sku()))
                .filter(java.util.Objects::nonNull)
                .filter(Product::active)
                .toList();

        long wireless = fixtures.stream()
                .filter(p -> p.title().toLowerCase().contains("wireless")
                        || p.tags().stream().anyMatch(t -> t.equalsIgnoreCase("wireless")))
                .count();
        check(report, failures, "wireless >= 6", wireless >= 6, wireless);

        long cheap = fixtures.stream()
                .filter(p -> p.price().compareTo(new BigDecimal("25")) < 0).count();
        long mid = fixtures.stream()
                .filter(p -> p.price().compareTo(new BigDecimal("25")) >= 0
                        && p.price().compareTo(new BigDecimal("100")) < 0).count();
        long premium = fixtures.stream()
                .filter(p -> p.price().compareTo(new BigDecimal("100")) >= 0).count();
        check(report, failures, "price bands >= 4", Math.min(cheap, Math.min(mid, premium)) >= 4,
                Map.of("cheap", cheap, "mid", mid, "premium", premium));

        // The generated expansion must itself satisfy the catalog shape rules.
        long generated = products.list(null, null, Boolean.TRUE, 0, 1).total()
                - (long) SeedPlan.NAMED_PRODUCTS.size() - SeedPlan.FILLERS.size();
        check(report, failures, "generated products present",
                generated == SeedPlan.GENERATED_COUNT, generated);

        long orders = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        check(report, failures, "40 orders", orders == 40, orders);

        Map<String, Long> orderBands = jdbc.query(
                "SELECT count(*) FILTER (WHERE total_amount < 30) AS cheap,"
                        + " count(*) FILTER (WHERE total_amount BETWEEN 30 AND 150) AS mid,"
                        + " count(*) FILTER (WHERE total_amount > 200) AS premium FROM orders",
                (rs, n) -> Map.of(
                        "cheap", rs.getLong("cheap"),
                        "mid", rs.getLong("mid"),
                        "premium", rs.getLong("premium")))
                .get(0);
        check(report, failures, "order bands >= 5",
                orderBands.values().stream().allMatch(v -> v >= 5), orderBands);

        for (String status : List.of("PENDING", "PROCESSING", "SHIPPED")) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = ?",
                    Long.class, status);
            check(report, failures, "status " + status + " >= 10", n >= 10, n);
        }

        Long mismatched = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE total_amount !="
                        + " (SELECT COALESCE(SUM(quantity * unit_price), 0) FROM order_items"
                        + " WHERE order_id = orders.id)", Long.class);
        check(report, failures, "totals == sum(lines)", mismatched == 0, mismatched);

        Long items = jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class);
        check(report, failures, "~85 items, avg >= 2", items >= 80, items);

        check(report, failures, "WM orders >= 8",
                countOrdersWithTitle("Wireless Mouse") >= 8, countOrdersWithTitle("Wireless Mouse"));
        check(report, failures, "keyboard orders >= 3",
                countOrdersWithTitle("Mechanical Keyboard") >= 3,
                countOrdersWithTitle("Mechanical Keyboard"));
        check(report, failures, "both >= 2", countOrdersWithBoth() >= 2, countOrdersWithBoth());

        Long wendy = jdbc.queryForObject(
                "SELECT count(DISTINCT o.id) FROM orders o JOIN users u ON u.id = o.user_id"
                        + " WHERE u.email = 'wendy.wireless@example.com' AND EXISTS"
                        + " (SELECT 1 FROM order_items WHERE order_id = o.id AND title IN"
                        + " ('Wireless Mouse','Wireless Earbuds','Wireless Charger Pad',"
                        + "'Wireless Keyboard Mini','Bluetooth Speaker','Wireless Presenter'))",
                Long.class);
        check(report, failures, "wendy wireless orders >= 3", wendy >= 3, wendy);
        return finish(report, failures, expectedOrders, all.total());
    }

    /** The catalog-shape and snapshot-preservation invariants, then the verdict. */
    private Map<String, Object> finish(Map<String, Object> report, List<String> failures,
                                       int expectedOrders, long productsSeen) {
        Product keyboard = products.getBySku("MK-002");
        check(report, failures, "keyboard >= 2 variants", keyboard.variants().size() >= 2,
                keyboard.variants().size());
        Product lamp = products.getBySku("DL-005");
        long lampColors = lamp.variants().stream().map(Variant::color).distinct().count();
        check(report, failures, "lamp >= 2 color variants", lampColors >= 2, lampColors);

        Product mouse = products.getBySku("WM-001");
        check(report, failures, "WM tags/attrs",
                mouse.tags().contains("wireless")
                        && mouse.attributes().containsKey("color")
                        && mouse.attributes().containsKey("dpi"),
                Map.of("tags", mouse.tags(), "attributes", mouse.attributes().keySet()));

        // Shape check across the fixtures and a page of the generated expansion.
        boolean shapeOk = productsSeen > 0
                && products.list(null, null, null, 0, 25).items().stream().allMatch(SeedService::wellFormed)
                && products.list("GEN-", null, Boolean.TRUE, 0, 100).items().stream().allMatch(SeedService::wellFormed);
        check(report, failures, "shape everywhere", shapeOk, shapeOk);

        long orders = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        long esCount = searchRepository.count();
        check(report, failures, "ES caught up", esCount == orders, Map.of("es", esCount, "pg", orders));

        // Snapshot preservation: the live catalog moved, history did not.
        Long history = jdbc.queryForObject(
                "SELECT count(*) FROM order_items WHERE title = 'Wireless Mouse'", Long.class);
        check(report, failures, "snapshot mismatch preserved",
                "Wireless Mouse Pro".equals(mouse.title())
                        && new BigDecimal("79.00").compareTo(mouse.price()) == 0
                        && history != null && history >= 8,
                Map.of("live", mouse.title(), "history", history));

        if (expectedOrders > 0 && orders != expectedOrders) {
            failures.add("40 orders: expected " + expectedOrders);
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("SEED VERIFY FAILED:\n- " + String.join("\n- ", failures));
        }
        return report;
    }

    /** The catalog shape every document — fixture or generated — must satisfy. */
    private static boolean wellFormed(Product p) {
        return p.sku() != null && !p.sku().isBlank()
                && p.title() != null && !p.title().isBlank()
                && p.price() != null && p.price().signum() >= 0
                && p.category() != null && !p.category().isBlank()
                && p.attributes() != null && !p.attributes().isEmpty()
                && p.variants() != null && !p.variants().isEmpty();
    }

    private long countOrdersWithTitle(String title) {
        Long n = jdbc.queryForObject(
                "SELECT count(DISTINCT order_id) FROM order_items WHERE title = ?",
                Long.class, title);
        return n == null ? 0 : n;
    }

    private long countOrdersWithBoth() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM orders o WHERE EXISTS"
                        + " (SELECT 1 FROM order_items WHERE order_id = o.id AND title = 'Wireless Mouse')"
                        + " AND EXISTS (SELECT 1 FROM order_items WHERE order_id = o.id"
                        + " AND title = 'Mechanical Keyboard')", Long.class);
        return n == null ? 0 : n;
    }

    private static void check(Map<String, Object> report, List<String> failures,
                              String name, boolean ok, Object detail) {
        report.put(name, detail);
        if (!ok) {
            failures.add(name + ": " + detail);
        }
    }
}