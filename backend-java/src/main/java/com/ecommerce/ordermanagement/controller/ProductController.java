package com.ecommerce.ordermanagement.controller;

import com.ecommerce.ordermanagement.model.ProductDtos.Product;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductCreate;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductPageResponse;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductUpdate;
import com.ecommerce.ordermanagement.repository.ProductRepository;
import com.ecommerce.ordermanagement.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Catalog routes. MongoDB only. */
@RestController
@RequestMapping("/api/products")
@Validated
public class ProductController {

    /** Page size used when the caller supplies none. */
    private static final int DEFAULT_PAGE_SIZE = 24;

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    /**
     * One page of the catalog.
     *
     * <p>Pagination happens in MongoDB ({@code skip}/{@code limit}); the JVM only
     * ever holds the requested page. The existing filters ({@code category},
     * {@code search}, {@code include_inactive}) are unchanged and compose with
     * paging. The legacy {@code skip}/{@code limit} parameters still work and are
     * honoured when {@code page}/{@code size} are absent.</p>
     */
    @GetMapping
    public ProductPageResponse listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean include_inactive,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(ProductRepository.MAX_PAGE_SIZE) Integer size,
            @RequestParam(required = false) @Min(0) Integer skip,
            @RequestParam(required = false) @Min(1) @Max(ProductRepository.MAX_PAGE_SIZE) Integer limit) {
        Boolean active = resolveActive(status, include_inactive);

        int effectiveSize = size != null ? size
                : limit != null ? limit
                : DEFAULT_PAGE_SIZE;
        // Guard the page*size product against overflowing int at absurd page numbers.
        long skipLong = page != null ? (long) page * effectiveSize
                : skip != null ? skip
                : 0L;
        int effectiveSkip = (int) Math.min(skipLong, Integer.MAX_VALUE);

        ProductRepository.ProductPage result =
                products.list(search, category, active, effectiveSkip, effectiveSize);

        int effectivePage = page != null ? page : (int) (effectiveSkip / effectiveSize);
        int totalPages = (int) Math.ceil((double) result.total() / effectiveSize);
        return new ProductPageResponse(
                result.items(),
                effectivePage,
                effectiveSize,
                result.total(),
                totalPages,
                result.hasNext(effectiveSkip, effectiveSize));
    }

    @GetMapping("/{productId}")
    public Product getProduct(@PathVariable String productId) {
        Product product = products.getById(productId);
        if (product == null) {
            throw ApiException.notFound("product " + productId + " not found");
        }
        return product;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Product createProduct(@Valid @RequestBody ProductCreate product) {
        validateCreate(product);
        try {
            products.ensureIndexes();
            return products.create(product);
        } catch (ProductRepository.DuplicateSku exc) {
            throw ApiException.conflict(exc.getMessage());
        }
    }

    @PutMapping("/{productId}")
    public Product updateProduct(@PathVariable String productId, @Valid @RequestBody ProductUpdate product) {
        try {
            products.ensureIndexes();
            Product updated = products.update(productId, product);
            if (updated == null) {
                throw ApiException.notFound("product " + productId + " not found");
            }
            return updated;
        } catch (ProductRepository.DuplicateSku exc) {
            throw ApiException.conflict(exc.getMessage());
        }
    }

    /**
     * Map the admin Status filter onto the {@code active} column.
     *
     * <p>{@code status} is the explicit admin control and wins outright:
     * {@code all} means no active filter at all, {@code active}/{@code inactive}
     * pin the flag. Only when {@code status} is absent does the older
     * {@code include_inactive} flag apply, which keeps every pre-existing caller
     * — the storefront and any direct API consumer — behaving exactly as before:
     * active products only, unless {@code include_inactive=true}.</p>
     */
    private static Boolean resolveActive(String status, boolean includeInactive) {
        if (status != null && !status.isBlank()) {
            if (status.equalsIgnoreCase("all")) {
                return null;
            }
            if (status.equalsIgnoreCase("active")) {
                return Boolean.TRUE;
            }
            if (status.equalsIgnoreCase("inactive")) {
                return Boolean.FALSE;
            }
            throw ApiException.unprocessable("status must be one of: all, active, inactive");
        }
        return includeInactive ? null : Boolean.TRUE;
    }

    /**
     * Catalog shape rules, mirroring the former pydantic model validator:
     * a bare document would not exercise the document-oriented part of MongoDB
     * that Screen 5 exists to demonstrate.
     */
    private static void validateCreate(ProductCreate product) {
        if (isBlank(product.sku())) {
            throw ApiException.unprocessable("sku is required (1-64 characters)");
        }
        if (isBlank(product.title())) {
            throw ApiException.unprocessable("title is required (1-200 characters)");
        }
        if (isBlank(product.category())) {
            throw ApiException.unprocessable("category is required (1-64 characters)");
        }
        if (product.price() == null) {
            throw ApiException.unprocessable("price is required");
        }
        if (product.variants().isEmpty()) {
            throw ApiException.unprocessable("a product must have at least one variant");
        }
        if (product.attributes().isEmpty()) {
            throw ApiException.unprocessable("a product must have at least one attribute");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}