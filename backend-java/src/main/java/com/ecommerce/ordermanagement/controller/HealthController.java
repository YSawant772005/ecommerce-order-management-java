package com.ecommerce.ordermanagement.controller;

import org.bson.Document;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness of the three stores. Never raises: a down store reports {@code DOWN},
 * so the frontend/Nginx health probe distinguishes "API up" from "store down".
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final MongoTemplate mongo;
    private final RestClient es;

    public HealthController(JdbcTemplate jdbc, MongoTemplate mongo, RestClient es) {
        this.jdbc = jdbc;
        this.mongo = mongo;
        this.es = es;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            out.put("postgres", "UP");
        } catch (Exception exc) {
            out.put("postgres", "DOWN");
        }
        try {
            mongo.getDb().runCommand(new Document("ping", 1));
            out.put("mongo", "UP");
        } catch (Exception exc) {
            out.put("mongo", "DOWN");
        }
        try {
            es.performRequest(new Request("GET", "/"));
            out.put("elasticsearch", "UP");
        } catch (Exception exc) {
            out.put("elasticsearch", "DOWN");
        }
        return out;
    }
}
