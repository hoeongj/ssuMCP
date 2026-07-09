package com.ssuai.global.config;

import java.util.List;

import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

final class ApiCorsDefaults {

    static final List<String> PUBLIC_READ_MAPPINGS = List.of(
            "/api/meals/today",
            "/api/meals/weekly",
            "/api/dorm/meals/this-week",
            "/api/notices",
            "/api/library/seats",
            "/api/library/seats/events",
            "/api/library/books",
            "/api/campus/facilities",
            "/api/academic-calendar"
    );

    private ApiCorsDefaults() {
    }

    public static void register(CorsRegistry registry, String origin) {
        register(registry, List.of(origin), List.of());
    }

    public static void register(CorsRegistry registry, List<String> origins, List<String> originPatterns) {
        String[] cleanOrigins = clean(origins);
        String[] cleanOriginPatterns = clean(originPatterns);

        for (String mapping : PUBLIC_READ_MAPPINGS) {
            CorsRegistration registration = registry.addMapping(mapping)
                    .allowedMethods("GET", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(false)
                    .maxAge(3600);
            if (cleanOrigins.length > 0) {
                registration.allowedOrigins(cleanOrigins);
            }
            if (cleanOriginPatterns.length > 0) {
                registration.allowedOriginPatterns(cleanOriginPatterns);
            }
        }
    }

    private static String[] clean(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }
}
