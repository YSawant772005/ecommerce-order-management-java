package com.ecommerce.ordermanagement.controller;

import com.ecommerce.ordermanagement.model.SyncStatusOut;
import com.ecommerce.ordermanagement.service.SyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sync badge and maintenance routes. PG + ES reads, never MongoDB. */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/status/{orderId}")
    public SyncStatusOut syncStatus(@PathVariable long orderId) {
        return syncService.syncStatus(orderId);
    }

    @PostMapping("/drain-outbox")
    public Map<String, Object> drainOutbox(@RequestParam(defaultValue = "100") int limit) {
        return syncService.drainOutbox(limit);
    }

    /** Delete, recreate, and fully repopulate the orders index from PG. */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return syncService.reindex();
    }
}