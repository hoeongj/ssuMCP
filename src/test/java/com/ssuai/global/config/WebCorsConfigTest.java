package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebCorsConfigTest {

    @Test
    void apiCorsMappingAllowsGetPostAndOptions() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfig().addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedMethods()).contains("GET", "POST", "OPTIONS");
    }

    @Test
    void apiCorsMappingAllowsCredentials() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfig().addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isTrue();
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {

        private CorsConfiguration corsConfiguration(String pathPattern) {
            return getCorsConfigurations().get(pathPattern);
        }
    }
}
