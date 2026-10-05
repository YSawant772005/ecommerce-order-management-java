package com.ecommerce.ordermanagement.repository;

import com.ecommerce.ordermanagement.model.ProductDtos.Product;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductCreate;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductUpdate;
import com.ecommerce.ordermanagement.model.ProductDtos.Variant;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog repository — the only place BSON {@code Decimal128} exists.
 *
 * <p>MongoDB has no decimal type, so the catalog's money is stored as
 * {@code Decimal128}. This class converts at the boundary and nothing else in
 * the codebase may touch {@code Decimal128}. It is the only MongoDB read on the
 * order path (cart pricing) and it never rewrites historical orders.</p>
 */
@Repository
public class ProductRepository {

    public static final String COLLECTION = "products";

    /** Raised when a create/update would violate the unique {@code sku} index. */
    public static class DuplicateSku extends RuntimeException {
        public DuplicateSku(String message) {
            super(message);
        }
    }

    /**
     * One page of catalog results, plus the unpaginated total that matched.
     * {@code total} is a real {@code countDocuments} for the filter, so the
     * caller never has to load the catalog to know how many pages exist.
     */
    public record ProductPage(List<Product> items, long total) {

        public boolean hasNext(long skip, int limit) {
            return skip + items.size() < total;
        }
    }

    /** Largest page a caller may request; keeps a single query bounded. */
    public static final int MAX_PAGE_SIZE = 100;

    private final MongoTemplate mongo;

    public ProductRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // -- conversions --------------------------------------------------------

    private static Decimal128 toDecimal128(BigDecimal value) {
        return new Decimal128(value);
    }

    private static Object toPlain(Object value) {
        if (value instanceof Decimal128 d) {
            return d.bigDecimalValue();
        }
        if (value instanceof Document doc) {
            Map<String, Object> out = new LinkedHashMap<>();
            doc.forEach((k, v) -> out.put(k, toPlain(v)));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toPlain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(toPlain(item));
            }
            return out;
        }
        return value;
    }

    private static OffsetDateTime asUtc(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        return null;
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Decimal128 d) {
            return d.bigDecimalValue();
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static Variant toVariant(Document doc) {
        return new Variant(
                doc.getString("sku"),
                doc.getString("color"),
                doc.getInteger("stock"),
                money(doc.get("price")));
    }

    private static Product toProduct(Document doc) {
        List<String> tags = doc.getList("tags", String.class);
        List<Document> variants = doc.getList("variants", Document.class);
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object attrs = doc.get("attributes");
        if (attrs instanceof Document attrDoc) {
            attrDoc.forEach((k, v) -> attributes.put(k, toPlain(v)));
        }
        return new Product(
                doc.getObjectId("_id").toHexString(),
                doc.getString("sku"),
                doc.getString("title"),
                doc.getString("description"),
                money(doc.get("price")),
                doc.getString("category"),
                tags == null ? List.of() : tags,
                attributes,
                variants == null ? List.of() : variants.stream().map(ProductRepository::toVariant).toList(),
                doc.getBoolean("active", true),
                asUtc(doc.get("updated_at")));
    }

    private static Document toDocument(ProductCreate create) {
        Document doc = new Document();
        applyFields(doc, create.sku(), create.title(), create.description(), create.price(),
                create.category(), create.tags(), create.attributes(), create.variants());
        doc.put("active", create.active() == null || create.active());
        return doc;
    }

    private static Document toPatch(ProductUpdate update) {
        Document patch = new Document();
        applyFields(patch, update.sku(), update.title(), update.description(), update.price(),
                update.category(), update.tags(), update.attributes(), update.variants());
        if (update.active() != null) {
            patch.put("active", update.active());
        }
        return patch;
    }

    private static void applyFields(
            Document doc, String sku, String title, String description, BigDecimal price,
            String category, List<String> tags, Map<String, Object> attributes, List<Variant> variants) {
        if (sku != null) {
            doc.put("sku", sku);
        }
        if (title != null) {
            doc.put("title", title);
        }
        if (description != null) {
            doc.put("description", description);
        }
        if (price != null) {
            doc.put("price", toDecimal128(price));
        }
        if (category != null) {
            doc.put("category", category);
        }
        if (tags != null) {
            doc.put("tags", tags);
        }
        if (attributes != null) {
            doc.put("attributes", new Document(attributes));
        }
        if (variants != null) {
            doc.put("variants", variants.stream().map(ProductRepository::toVariantDocument).toList());
        }
    }

    private static Document toVariantDocument(Variant variant) {
        Document doc = new Document();
        doc.put("sku", variant.sku());
        doc.put("color", variant.color());
        doc.put("stock", variant.stock() == null ? 0 : variant.stock());
        doc.put("price", variant.price() == null ? null : toDecimal128(variant.price()));
        return doc;
    }

    // -- indexes ------------------------------------------------------------

    /** The three catalog indexes: unique sku, (category, active), tags. */
    public void ensureIndexes() {
        mongo.indexOps(COLLECTION).ensureIndex(
                new Index().on("sku", Sort.Direction.ASC).unique().named("sku_unique"));
        mongo.indexOps(COLLECTION).ensureIndex(
                new Index().on("category", Sort.Direction.ASC).on("active", Sort.Direction.ASC)
                        .named("category_active"));
        mongo.indexOps(COLLECTION).ensureIndex(
                new Index().on("tags", Sort.Direction.ASC).named("tags_idx"));
    }

    // -- reads --------------------------------------------------------------

    /** Filtered page plus the total match count (Screen 5, MongoDB only). */
    public ProductPage list(String search, String category, Boolean active, int skip, int limit) {
        List<Criteria> parts = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            parts.add(new Criteria().orOperator(
                    Criteria.where("title").regex(search, "i"),
                    Criteria.where("sku").regex(search, "i"),
                    Criteria.where("description").regex(search, "i")));
        }
        if (category != null) {
            parts.add(Criteria.where("category").is(category));
        }
        if (active != null) {
            parts.add(Criteria.where("active").is(active));
        }
        Criteria criteria = parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts);
        long total = mongo.count(new Query(criteria), COLLECTION);
        // Sort by title for a human-sensible grid, with _id as the tie-breaker so
        // the order is total: no product can land on two different pages, or on
        // neither, as the catalog grows.
        Query find = new Query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "title").and(Sort.by(Sort.Direction.ASC, "_id")))
                .skip(skip).limit(limit);
        List<Product> items = mongo.find(find, Document.class, COLLECTION).stream()
                .map(ProductRepository::toProduct)
                .toList();
        return new ProductPage(items, total);
    }

    public Product getById(String productId) {
        if (!ObjectId.isValid(productId)) {
            return null;
        }
        Document doc = mongo.findOne(
                new Query(Criteria.where("_id").is(new ObjectId(productId))), Document.class, COLLECTION);
        return doc == null ? null : toProduct(doc);
    }

    public Product getBySku(String sku) {
        Document doc = mongo.findOne(new Query(Criteria.where("sku").is(sku)), Document.class, COLLECTION);
        return doc == null ? null : toProduct(doc);
    }

    /** Batch lookup for cart pricing: one {@code $in} query, not one per line. */
    public Map<String, Product> getManyByIds(List<String> productIds) {
        List<ObjectId> oids = productIds.stream().filter(ObjectId::isValid).map(ObjectId::new).toList();
        Map<String, Product> found = new LinkedHashMap<>();
        if (oids.isEmpty()) {
            return found;
        }
        for (Document doc : mongo.find(
                new Query(Criteria.where("_id").in(oids)), Document.class, COLLECTION)) {
            Product product = toProduct(doc);
            found.put(product.id(), product);
        }
        return found;
    }

    // -- writes -------------------------------------------------------------

    public Product create(ProductCreate create) {
        Document doc = toDocument(create);
        doc.put("updated_at", new Date());
        try {
            mongo.insert(doc, COLLECTION);
        } catch (DuplicateKeyException exc) {
            throw new DuplicateSku("sku already exists: " + create.sku());
        }
        return toProduct(doc);
    }

    /**
     * Bulk-insert products. Used only by the seeder's generated catalog expansion:
     * one {@code insertMany} per chunk instead of thousands of round trips.
     * Document shape and Decimal128 handling are identical to {@link #create}.
     */
    public void createAll(List<ProductCreate> creates) {
        for (int start = 0; start < creates.size(); start += 500) {
            List<Document> docs = new ArrayList<>(
                    creates.subList(start, Math.min(start + 500, creates.size())).stream()
                            .map(c -> {
                                Document doc = toDocument(c);
                                doc.put("updated_at", new Date());
                                return doc;
                            })
                            .toList());
            mongo.insert(docs, COLLECTION);
        }
    }

    public Product update(String productId, ProductUpdate update) {
        if (!ObjectId.isValid(productId)) {
            return null;
        }
        Document patch = toPatch(update);
        patch.put("updated_at", new Date());
        Update updateSpec = new Update();
        patch.forEach(updateSpec::set);

        try {
            Document doc = mongo.findAndModify(
                    new Query(Criteria.where("_id").is(new ObjectId(productId))),
                    updateSpec,
                    FindAndModifyOptions.options().returnNew(true),
                    Document.class,
                    COLLECTION);
            return doc == null ? null : toProduct(doc);
        } catch (DuplicateKeyException exc) {
            throw new DuplicateSku("sku already exists");
        }
    }

    public boolean delete(String productId) {
        if (!ObjectId.isValid(productId)) {
            return false;
        }
        var result = mongo.remove(
                new Query(Criteria.where("_id").is(new ObjectId(productId))), COLLECTION);
        return result.getDeletedCount() == 1;
    }

    /** Drop the catalog collection; used by the seeder's {@code --reset}. */
    public void dropCollection() {
        mongo.dropCollection(COLLECTION);
    }
}
