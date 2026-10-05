package com.ecommerce.ordermanagement.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every connection string and switch the application needs.
 *
 * <p>Field names mirror the environment variables the former FastAPI settings
 * read, so the Compose files, {@code .env.example} and the single-container
 * entrypoint keep working unchanged.</p>
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String pgDsn;
    private String rabbitmqUrl;
    private String esOrdersIndex = "orders";
    private String corsOrigins = "http://localhost:5173";
    private String orderSyncStrategy = "dual_write";
    private String orderSyncPlacementStatus = "QUEUED";
    private String seedAnchorDate = "2026-09-30T00:00:00Z";
    private int seedDefault = 42;

    /**
     * Strategy 1 — asynchronous queued dual-write — is the only implemented
     * strategy. Assigning anything else is a configuration error, exactly as
     * the former pydantic {@code Literal["dual_write"]} rejected {@code polling}.
     */
    @PostConstruct
    void validate() {
        if (!"dual_write".equals(orderSyncStrategy)) {
            throw new IllegalStateException(
                    "ORDER_SYNC_STRATEGY must be 'dual_write' (Strategy 1 is the only "
                            + "implemented synchronization strategy); got '" + orderSyncStrategy + "'");
        }
    }

    public String getPgDsn() {
        return pgDsn;
    }

    public void setPgDsn(String pgDsn) {
        this.pgDsn = pgDsn;
    }

    public String getRabbitmqUrl() {
        return rabbitmqUrl;
    }

    public void setRabbitmqUrl(String rabbitmqUrl) {
        this.rabbitmqUrl = rabbitmqUrl;
    }

    public String getEsOrdersIndex() {
        return esOrdersIndex;
    }

    public void setEsOrdersIndex(String esOrdersIndex) {
        this.esOrdersIndex = esOrdersIndex;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public String getOrderSyncStrategy() {
        return orderSyncStrategy;
    }

    public void setOrderSyncStrategy(String orderSyncStrategy) {
        this.orderSyncStrategy = orderSyncStrategy;
    }

    public String getOrderSyncPlacementStatus() {
        return orderSyncPlacementStatus;
    }

    public void setOrderSyncPlacementStatus(String orderSyncPlacementStatus) {
        this.orderSyncPlacementStatus = orderSyncPlacementStatus;
    }

    public String getSeedAnchorDate() {
        return seedAnchorDate;
    }

    public void setSeedAnchorDate(String seedAnchorDate) {
        this.seedAnchorDate = seedAnchorDate;
    }

    public int getSeedDefault() {
        return seedDefault;
    }

    public void setSeedDefault(int seedDefault) {
        this.seedDefault = seedDefault;
    }
}
