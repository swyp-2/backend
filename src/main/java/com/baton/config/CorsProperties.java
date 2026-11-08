package com.baton.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins != null
                ? allowedOrigins.stream().filter(origin -> !origin.isBlank()).toList()
                : List.of();
    }
}
