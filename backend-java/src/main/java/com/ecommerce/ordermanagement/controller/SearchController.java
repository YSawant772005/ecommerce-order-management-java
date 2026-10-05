package com.ecommerce.ordermanagement.controller;

import com.ecommerce.ordermanagement.model.SearchDtos.SearchRequest;
import com.ecommerce.ordermanagement.model.SearchDtos.SearchResponse;
import com.ecommerce.ordermanagement.service.SearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin search route. Elasticsearch only. */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/orders")
    public SearchResponse searchOrders(@Valid @RequestBody SearchRequest req) {
        return searchService.searchOrders(req);
    }
}