package com.ecommerce.ordermanagement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS, matching the former FastAPI {@code CORSMiddleware} configuration
 * (origins from {@code CORS_ORIGINS}, all methods/headers, credentials allowed).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(props.getCorsOrigins().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
