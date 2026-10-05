package com.ecommerce.ordermanagement.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * The single Elasticsearch client.
 *
 * <p>Built on the low-level {@code RestClient} so the request bodies are the
 * same JSON the former elasticsearch-py client sent — the mapping, the index
 * with {@code version_type=external}, and the search body are all byte-for-byte
 * the same documents.</p>
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient esRestClient(@Value("${spring.elasticsearch.uris:http://127.0.0.1:9200}") String uris) {
        HttpHost[] hosts = Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        return RestClient.builder(hosts)
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(5000)
                        .setSocketTimeout(30000))
                .build();
    }

    /**
     * Fail fast when MongoDB is down. These are the 5s values the former motor
     * client used; without them the driver's 30s server-selection timeout stalls
     * {@code /api/health} while the store is unreachable. Set here rather than
     * under {@code spring.data.mongodb.options} because an explicit
     * {@code uri} takes precedence over those options.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoTimeoutCustomizer() {
        return builder -> builder
                .applyToClusterSettings(settings -> settings.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(settings -> settings
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS));
    }
}
