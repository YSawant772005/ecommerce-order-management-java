package com.ecommerce.ordermanagement.worker;

import com.ecommerce.ordermanagement.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The former Celery beat schedule: drain the outbox every 15 seconds so any
 * event the broker lost, and any terminal-by-attempts gap, is replayed.
 */
@Component
public class DrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(DrainScheduler.class);

    private final SyncService syncService;

    public DrainScheduler(SyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void drain() {
        try {
            syncService.drainOutbox(100);
        } catch (Exception exc) {
            log.warn("outbox drain failed (will retry next tick): {}", exc.getMessage());
        }
    }
}
