package com.ssuai.global.config;

import org.springframework.web.servlet.config.annotation.CorsRegistry;

final class ApiCorsDefaults {

    private ApiCorsDefaults() {
    }

    public static void register(CorsRegistry registry, String origin) {
        registry.addMapping("/api/**")
                .allowedOrigins(origin)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
