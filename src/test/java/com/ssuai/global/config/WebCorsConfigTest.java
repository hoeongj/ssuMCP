package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebCorsConfigTest {

    @Test
    void publicCorsMappingsAllowGetAndOptions() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfig().addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/meals/today");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedMethods()).containsExactly("GET", "OPTIONS");
        assertThat(registry.pathPatterns())
                .containsExactlyInAnyOrderElementsOf(ApiCorsDefaults.PUBLIC_READ_MAPPINGS);
    }

    @Test
    void publicCorsMappingsDoNotAllowCredentials() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfig().addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/library/seats/events");
        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    void publicPreflightAllowsLocalhostButAuthenticatedEndpointHasNoCorsMapping() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsConfig().addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/library/seats");
        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(config.checkOrigin("http://127.0.0.1:3000")).isEqualTo("http://127.0.0.1:3000");
        assertThat(config.checkHttpMethod(HttpMethod.GET)).contains(HttpMethod.GET);
        assertThat(config.checkHeaders(List.of("accept"))).contains("accept");

        assertThat(registry.corsConfiguration("/api/auth/refresh")).isNull();
        assertThat(registry.corsConfiguration("/api/library/loans")).isNull();
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {

        private CorsConfiguration corsConfiguration(String pathPattern) {
            return getCorsConfigurations().get(pathPattern);
        }

        private java.util.Set<String> pathPatterns() {
            return getCorsConfigurations().keySet();
        }
    }
}
