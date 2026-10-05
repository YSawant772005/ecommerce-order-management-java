package com.ecommerce.ordermanagement.config;

import com.ecommerce.ordermanagement.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ensures the ES orders index exists at startup. Best-effort: a down
 * Elasticsearch must not prevent the API from serving PostgreSQL/Mongo reads.
 */
@Component
@Order(1)
public class IndexBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final SearchRepository searchRepository;

    public IndexBootstrap(SearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            searchRepository.ensureOrdersIndex();
        } catch (Exception exc) {
            log.warn("Elasticsearch orders index not ensured at startup: {}", exc.getMessage());
        }
    }
}