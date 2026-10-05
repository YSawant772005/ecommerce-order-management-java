package com.ecommerce.ordermanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * E-commerce Order Management — Spring Boot port of the FastAPI backend.
 *
 * <p>{@code @EnableScheduling} drives the outbox drain that the former Celery
 * beat ran every 15 seconds.</p>
 */
@SpringBootApplication
@EnableScheduling
public class EcommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }
}

