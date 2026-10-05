package com.ecommerce.ordermanagement.seed;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The deterministic seed plan: a <b>pure</b> construction of 40 order slots with
 * roles assigned. No database, no clock, no module-level RNG — every draw comes
 * from one local {@link Random}, so two runs with the same seed and anchor date
 * produce the same plan.
 *
 * <p>Roles overlap by design (40 slots against ~71 role minimums); the verifier
 * in {@link SeedService} checks the persisted result, not the plan.</p>
 */
public final class SeedPlan {

    private SeedPlan() {
    }

    public record UserSeed(String name, String email) {
    }

    public record ProductSeed(
            String sku,
            String title,
            String price,
            String category,
            List<String> tags,
            Map<String, Object> attributes) {
    }

    /** One planned cart line: which catalog product, and how many. */
    public record Line(int productIndex, int quantity) {
    }

    /** One planned order: lines plus owner, status and date. */
    public record OrderSpec(int userIdx, List<Line> lines, String status, OffsetDateTime date) {
    }

    public static final List<UserSeed> USERS = List.of(
            new UserSeed("John Doe", "john.doe@example.com"),
            new UserSeed("Jane Smith", "jane.smith@example.com"),
            new UserSeed("Wendy Wireless", "wendy.wireless@example.com"),
            new UserSeed("Alex Rivera", "alex.rivera@example.com"),
            new UserSeed("Sam Patel", "sam.patel@example.com"),
            new UserSeed("Casey Nguyen", "casey.nguyen@example.com"),
            new UserSeed("Morgan Lee", "morgan.lee@example.com"),
            new UserSeed("Riley Brooks", "riley.brooks@example.com"));

    private static ProductSeed named(String sku, String title, String price, String category,
                                     List<String> tags, Map<String, Object> attributes) {
        return new ProductSeed(sku, title, price, category, tags, attributes);
    }

    private static Map<String, Object> attrs(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** Six named products; plan indices 0..5. */
    public static final List<ProductSeed> NAMED_PRODUCTS = List.of(
            named("WM-001", "Wireless Mouse", "50.16", "peripherals",
                    List.of("wireless", "usb", "office"), attrs("color", "black", "dpi", 1600)),
            named("MK-002", "Mechanical Keyboard", "100.34", "peripherals",
                    List.of("keyboard", "rgb"), attrs("switch", "brown", "layout", "TKL")),
            named("WE-003", "Wireless Earbuds", "79.99", "audio",
                    List.of("wireless", "bluetooth"), attrs("battery_hours", 24, "color", "white")),
            named("UH-004", "USB-C Hub 7-in-1", "45.00", "cables",
                    List.of("usb-c", "hub"), attrs("ports", "hdmi,usb-a,usb-c,sd,ethernet")),
            named("DL-005", "Desk Lamp LED", "32.50", "office",
                    List.of("lamp", "led"), attrs("color_temp", "3000K-6000K", "power", "12W")),
            named("NH-006", "Noise Cancelling Headphones", "199.00", "audio",
                    List.of("bluetooth", "anc"), attrs("battery_hours", 40, "color", "black")));

    /** Eighteen fillers; plan indices 6..23. */
    public static final List<ProductSeed> FILLERS = List.of(
            named("HDMI Cable 2m", "HDMI Cable 2m", "12.99", "cables",
                    List.of("hdmi", "cable"), attrs("length_m", 2)),
            named("USB-C Cable 1m", "USB-C Cable 1m", "9.99", "cables",
                    List.of("usb-c", "cable"), attrs("length_m", 1)),
            named("Wireless Charger Pad", "Wireless Charger Pad", "24.99", "cables",
                    List.of("wireless", "charging"), attrs("watts", 15)),
            named("Ethernet Cable 5m", "Ethernet Cable 5m", "14.99", "cables",
                    List.of("ethernet", "cable"), attrs("length_m", 5)),
            named("Cable Organizer Kit", "Cable Organizer Kit", "19.99", "cables",
                    List.of("organizer"), attrs("pieces", 20)),
            named("Mouse Pad XL", "Mouse Pad XL", "22.99", "peripherals",
                    List.of("desk"), attrs("size", "XL")),
            named("Wireless Keyboard Mini", "Wireless Keyboard Mini", "59.99", "peripherals",
                    List.of("wireless", "keyboard"), attrs("layout", "60%")),
            named("Webcam 1080p", "Webcam 1080p", "69.99", "peripherals",
                    List.of("video"), attrs("resolution", "1080p")),
            named("Laptop Stand Aluminum", "Laptop Stand Aluminum", "39.99", "peripherals",
                    List.of("stand"), attrs("material", "aluminum")),
            named("Bluetooth Speaker", "Bluetooth Speaker", "89.99", "audio",
                    List.of("wireless", "bluetooth"), attrs("watts", 20)),
            named("Wired Earbuds", "Wired Earbuds", "15.99", "audio",
                    List.of("audio"), attrs("color", "black")),
            named("Studio Monitor Headphones", "Studio Monitor Headphones", "149.00", "audio",
                    List.of("audio", "studio"), attrs("impedance", "32ohm")),
            named("USB Microphone", "USB Microphone", "119.00", "audio",
                    List.of("audio", "mic"), attrs("pattern", "cardioid")),
            named("Notebook Set", "Notebook Set", "18.99", "office",
                    List.of("paper"), attrs("count", 3)),
            named("Wireless Presenter", "Wireless Presenter", "29.99", "office",
                    List.of("wireless", "office"), attrs("range_m", 30)),
            named("Desk Organizer", "Desk Organizer", "27.99", "office",
                    List.of("desk"), attrs("trays", 4)),
            named("Monitor Light Bar", "Monitor Light Bar", "49.99", "office",
                    List.of("lamp", "monitor"), attrs("power", "5W")),
            named("Ergonomic Foot Rest", "Ergonomic Foot Rest", "34.99", "office",
                    List.of("ergonomic"), attrs("material", "foam")));

    /** The one inactive product, so `include_inactive` and 409 paths are demonstrable. */
    public static final ProductSeed INACTIVE_PRODUCT =
            named("OLD-999", "Retired Dock", "199.99", "cables",
                    List.of("retired"), attrs("reason", "discontinued"));

    public static final List<String> CATEGORIES =
            List.of("peripherals", "audio", "cables", "office");

    // -- generated catalog expansion -----------------------------------------
    //
    // The storefront must stay usable with a catalog too large to render at
    // once. The 25 hand-written seeds above stay exactly as they are — they are
    // the fixtures orders, tests and the snapshot-mismatch check depend on.
    // Below them sits a deterministic generated catalog that scales the storefront
    // without touching any of that behaviour.

    /** How many additional products the generated catalog contributes. */
    public static final int GENERATED_COUNT = 3500;

    /** Total catalog size: 6 named + 18 fillers + 1 inactive + generated. */
    public static final int EXPECTED_PRODUCTS =
            NAMED_PRODUCTS.size() + FILLERS.size() + 1 + GENERATED_COUNT;

    /** Active subset of {@link #EXPECTED_PRODUCTS} (the inactive fixture is excluded). */
    public static final int EXPECTED_ACTIVE = EXPECTED_PRODUCTS - 1;

    /** Deterministic catalog size, kept out of the order-plan RNG stream. */
    private static final long GENERATED_SALT = 0x5EEDCA7A10L;

    /** Per-category vocabulary: adjectives, nouns and attribute keys. */
    record Vocabulary(List<String> adjectives, List<String> nouns, List<String> keys) {
    }

    private static final Map<String, Vocabulary> GENERATED_VOCABULARY = Map.of(
            "peripherals", new Vocabulary(
                    List.of("Wireless", "Ergonomic", "Compact", "Ultra", "Pro", "Mini",
                            "Gaming", "Travel", "Silent", "Adjustable", "Premium", "Smart"),
                    List.of("Mouse", "Keyboard", "Webcam", "Trackpad", "Mouse Pad",
                            "Laptop Stand", "KVM Switch", "Stylus", "Keypad", "Headset Stand"),
                    List.of("dpi", "color", "layout", "polling_rate", "warranty_months")),
            "audio", new Vocabulary(
                    List.of("Wireless", "Noise Cancelling", "Studio", "Portable", "Compact",
                            "Hi-Fi", "Gaming", "Smart", "Premium", "Mini", "Open-Back", "Digital"),
                    List.of("Earbuds", "Headphones", "Speaker", "Microphone", "Soundbar",
                            "Amplifier", "Audio Interface", "Turntable", "Headset", "DAC"),
                    List.of("battery_hours", "impedance", "color", "watts", "channels")),
            "cables", new Vocabulary(
                    List.of("USB-C", "Braided", "Right-Angle", "Fast-Charging", "Extended",
                            "Compact", "Travel", "Pro", "Dual", "Premium", "Angled", "Flexible"),
                    List.of("Cable", "Hub", "Charger", "Adapter", "Splitter", "Extender",
                            "Converter", "Docking Station", "Power Strip"),
                    List.of("length_m", "watts", "ports", "color", "warranty_months")),
            "office", new Vocabulary(
                    List.of("Adjustable", "Ergonomic", "Compact", "LED", "Premium", "Smart",
                            "Standing", "Minimal", "Deluxe", "Portable", "Rechargeable",
                            "Executive"),
                    List.of("Desk Lamp", "Notebook Set", "Desk Organizer", "Monitor Arm",
                            "Document Tray", "Pen Set", "Foot Rest", "Whiteboard", "Chair Mat",
                            "Tidy Bin"),
                    List.of("color_temp", "power", "material", "trays", "count")));

    /** Model suffixes, so generated titles vary the way a real catalog does. */
    private static final List<String> SERIES =
            List.of("Mk I", "Mk II", "Mk III", "Plus", "SE", "Pro", "Max", "Lite", "Prime", "Neo");

    private static final List<String> COLORS =
            List.of("black", "white", "silver", "graphite", "navy", "sage", "sand", "charcoal");

    /**
     * Build the generated catalog expansion. Deterministic: the same {@code seed}
     * always yields the same products in the same order. The generator uses a
     * salted RNG so adding products never perturbs the 40-order plan.
     */
    public static List<ProductSeed> generatedCatalog(long seed) {
        Random rng = new Random(seed + GENERATED_SALT);
        List<String> categories = List.copyOf(CATEGORIES);
        List<ProductSeed> out = new ArrayList<>(GENERATED_COUNT);

        for (int i = 0; i < GENERATED_COUNT; i++) {
            String category = categories.get(rng.nextInt(categories.size()));
            Vocabulary vocab = GENERATED_VOCABULARY.get(category);
            String adjective = vocab.adjectives().get(rng.nextInt(vocab.adjectives().size()));
            String noun = vocab.nouns().get(rng.nextInt(vocab.nouns().size()));
            String series = SERIES.get(rng.nextInt(SERIES.size()));

            // ~70% carry a series suffix, the rest are the plain product name.
            String title = rng.nextInt(10) < 7
                    ? adjective + " " + noun + " " + series
                    : adjective + " " + noun;

            // Price band per category, in cents, so generated prices span the
            // same cheap/mid/premium bands the 25 hand-written seeds do.
            int centsTotal = switch (category) {
                case "audio" -> 4000 + rng.nextInt(16000);
                case "peripherals" -> 1500 + rng.nextInt(9000);
                case "office" -> 1200 + rng.nextInt(8000);
                default -> 800 + rng.nextInt(4000);          // cables
            };
            String price = String.format("%.2f", centsTotal / 100.0);

            String color = COLORS.get(rng.nextInt(COLORS.size()));
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put(vocab.keys().get(rng.nextInt(vocab.keys().size())), rng.nextInt(2000) + 1);
            attributes.put("color", color);
            if (rng.nextBoolean()) {
                attributes.put("warranty_months", 12 + rng.nextInt(36));
            }

            List<String> tags = List.of(
                    category,
                    rng.nextBoolean() ? "wireless" : "usb",
                    rng.nextBoolean() ? "office" : "home");

            out.add(new ProductSeed(
                    String.format("GEN-%05d", i), title, price, category, tags, attributes));
        }
        return out;
    }

    /** The catalog title behind a plan's product index. */
    public static String titleFor(int index) {
        if (index < NAMED_PRODUCTS.size()) {
            return NAMED_PRODUCTS.get(index).title();
        }
        return FILLERS.get(index - NAMED_PRODUCTS.size()).title();
    }

    /** Deterministic sample of {@code k} elements; order is not significant. */
    static List<Integer> sample(Random rng, List<Integer> pool, int k) {
        List<Integer> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rng);
        return new ArrayList<>(copy.subList(0, Math.min(k, copy.size())));
    }

    static Line line(int productIndex, int quantity) {
        return new Line(productIndex, quantity);
    }

    /**
     * Build 40 order slots: cheap (<$30), mid and premium (>$200) bands each
     * survive; eight orders contain the Wireless Mouse, three the Mechanical
     * Keyboard; five belong to Wendy Wireless; five sit 61-90 days back and five
     * inside the last 7 days.
     */
    public static List<OrderSpec> buildOrderPlan(long seed, OffsetDateTime anchor) {
        Random rng = new Random(seed);

        List<String> statuses = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            statuses.add("PENDING");
        }
        for (int i = 0; i < 13; i++) {
            statuses.add("PROCESSING");
        }
        for (int i = 0; i < 13; i++) {
            statuses.add("SHIPPED");
        }
        Collections.shuffle(statuses, rng);

        // Product indices: 0=WM, 1=keyboard; fillers start at 6.
        List<Integer> cheap = List.of(6, 7, 8, 9, 10, 11, 16, 19);   // all <$25 fillers
        List<Integer> premium = List.of(1, 5, 17, 18);              // all >=$100
        List<Integer> all = new ArrayList<>();
        List<Integer> slotIds = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            all.add(i);
        }
        for (int i = 0; i < 40; i++) {
            slotIds.add(i);
        }

        // Forced fixtures are drawn first so the band roles survive intact.
        List<Integer> wmOrders = sample(rng, slotIds, 8);
        List<Integer> kbPool = new ArrayList<>(slotIds);
        kbPool.removeAll(wmOrders);
        List<Integer> kbOrders = sample(rng, kbPool, 3);
        kbOrders.addAll(sample(rng, wmOrders, 2));   // two orders contain both
        List<Integer> wendyOrders = sample(rng, slotIds, 5);

        // Old orders belong to Riley/Morgan (date-range edge), never to Wendy.
        List<Integer> notWendy = new ArrayList<>(slotIds);
        notWendy.removeAll(wendyOrders);
        List<Integer> oldOrders = sample(rng, notWendy, 5);
        List<Integer> notOld = new ArrayList<>(slotIds);
        notOld.removeAll(oldOrders);
        List<Integer> newOrders = sample(rng, notOld, 5);

        List<Integer> taken = new ArrayList<>(wmOrders);
        taken.addAll(kbOrders);
        // Cheap/rich bands must survive intact: no fixture lines, no Wendy top-up.
        List<Integer> plain = new ArrayList<>(slotIds);
        plain.removeAll(taken);
        plain.removeAll(wendyOrders);
        List<Integer> cheapOrders = sample(rng, plain, 5);
        List<Integer> notCheap = new ArrayList<>(plain);
        notCheap.removeAll(cheapOrders);
        List<Integer> richOrders = sample(rng, notCheap, 5);
        List<Integer> oneItem = sample(rng, slotIds, 1);
        List<Integer> manyItemPool = new ArrayList<>(slotIds);
        manyItemPool.removeAll(oneItem);
        List<Integer> manyItem = sample(rng, manyItemPool, 1);

        List<OrderSpec> orders = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int userIdx = i % 8;
            // Assigned by exactly one branch below, mirroring seed.py's
            // if/elif chain. A previous version evaluated linesFor("mid", ...)
            // unconditionally here and discarded it, burning RNG draws the
            // reference never makes and desynchronising the whole stream.
            List<Line> lines;

            if (wmOrders.contains(i) && kbOrders.contains(i)) {
                List<Line> rest = linesFor(rng, "mid", all, cheap, premium);
                lines = new ArrayList<>(List.of(line(0, 1), line(1, 1)));
                lines.addAll(firstOf(rest));
            } else if (wmOrders.contains(i)) {
                // seed.py: [(0, randint(1,2))] + (lines_for("mid")[:1] if rng.random() < 0.5 else [])
                lines = new ArrayList<>(List.of(line(0, randint(rng, 1, 2))));
                if (rng.nextDouble() < 0.5) {
                    lines.addAll(firstOf(linesFor(rng, "mid", all, cheap, premium)));
                }
            } else if (kbOrders.contains(i)) {
                // seed.py: [(1, 1)] + (lines_for("mid")[:1] if rng.random() < 0.5 else [])
                // The base quantity is pinned to 1, exactly as in the reference.
                lines = new ArrayList<>(List.of(line(1, 1)));
                if (rng.nextDouble() < 0.5) {
                    lines.addAll(firstOf(linesFor(rng, "mid", all, cheap, premium)));
                }
            } else if (cheapOrders.contains(i)) {
                lines = linesFor(rng, "cheap", all, cheap, premium);
            } else if (richOrders.contains(i)) {
                lines = linesFor(rng, "rich", all, cheap, premium);
            } else if (oneItem.contains(i)) {
                lines = new ArrayList<>(List.of(line(rng.nextInt(24), 1)));
            } else if (manyItem.contains(i)) {
                lines = new ArrayList<>();
                for (int p : sample(rng, all, 4)) {
                    lines.add(line(p, randint(rng, 1, 2)));
                }
            } else {
                lines = linesFor(rng, "mid", all, cheap, premium);
            }

            if (wendyOrders.contains(i)) {
                userIdx = 2;
            } else if (oldOrders.contains(i)) {
                userIdx = oldOrders.indexOf(i) % 2 == 0 ? 7 : 6;
            }
            if (wendyOrders.contains(i) && wendyOrders.indexOf(i) < 3 && !containsWireless(lines)) {
                lines.add(0, line(2, 1));
            }

            int day;
            if (oldOrders.contains(i)) {
                day = randint(rng, 61, 90);
            } else if (newOrders.contains(i)) {
                day = randint(rng, 0, 6);
            } else {
                day = randint(rng, 7, 60);
            }
            orders.add(new OrderSpec(userIdx, lines, statuses.get(i), anchor.minusDays(day)));
        }
        return orders;
    }

    private static final List<Integer> LINE_COUNT_CHOICES = List.of(1, 2, 2, 3, 4);

    /**
     * Equivalent to Python's {@code lines_for(... )[:1]}: take at most the first
     * element of the generated lines.
     */
    private static List<Line> firstOf(List<Line> lines) {
        return lines.isEmpty() ? List.of() : List.of(lines.get(0));
    }

    private static boolean containsWireless(List<Line> lines) {
        List<Integer> wireless = List.of(0, 2, 8, 12, 15, 20);
        for (Line line : lines) {
            if (wireless.contains(line.productIndex())) {
                return true;
            }
        }
        return false;
    }

    private static int randint(Random rng, int min, int maxInclusive) {
        return min + rng.nextInt(maxInclusive - min + 1);
    }

    private static List<Line> linesFor(Random rng, String kind, List<Integer> all,
                                        List<Integer> cheap, List<Integer> premium) {
        if (kind.equals("cheap")) {
            // Single unit of a sub-$25 filler: total always lands under $30.
            return new ArrayList<>(List.of(line(cheap.get(rng.nextInt(cheap.size())), 1)));
        }
        if (kind.equals("rich")) {
            List<Integer> picks = sample(rng, premium, 2);
            return new ArrayList<>(List.of(line(picks.get(0), 2), line(picks.get(1), 1)));
        }
        // Mirrors seed.py's rng.choice([1, 2, 2, 3, 4]). The list has FIVE entries, so
// the bound must be 5; nextInt(4) could never yield 4 and made 4-line orders
// impossible.
int n = LINE_COUNT_CHOICES.get(rng.nextInt(LINE_COUNT_CHOICES.size()));
        List<Line> lines = new ArrayList<>();
        for (int p : sample(rng, all, Math.min(n, all.size()))) {
            lines.add(line(p, randint(rng, 1, 3)));
        }
        return lines;
    }
}