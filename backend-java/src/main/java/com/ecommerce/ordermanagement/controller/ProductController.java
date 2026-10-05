package com.ecommerce.ordermanagement.controller;

import com.ecommerce.ordermanagement.model.ProductDtos.Product;
import com.ecommerce.ordermanagement.model.ProductDtos.ProductCreate;
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

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public List<Product> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean include_inactive,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") @Min(0) int skip,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        Boolean active = include_inactive ? null : Boolean.TRUE;
        return products.list(search, category, active, skip, limit).items();
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