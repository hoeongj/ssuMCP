package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebCorsProdConfigTest {

    @Test
    void beanLoadsUnderProdProfileWhenFrontendOriginIsSet() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(WebCorsProdConfig.class)
                .withPropertyValues("ssuai.frontend.origin=https://test.example.com")
                .run(ctx -> assertThat(ctx).hasSingleBean(WebCorsProdConfig.class));
    }

    @Test
    void publicCorsMappingAllowsGetAndOptions() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsProdConfig("https://test.example.com").addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/meals/today");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedMethods()).containsExactly("GET", "OPTIONS");
        assertThat(registry.pathPatterns())
                .containsExactlyInAnyOrderElementsOf(ApiCorsDefaults.PUBLIC_READ_MAPPINGS);
    }

    @Test
    void publicCorsMappingDoesNotAllowCredentialsUnderProd() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsProdConfig("https://test.example.com").addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/library/seats/events");
        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    void publicPreflightAllowsProdAndPreviewOriginsButNotAuthenticatedEndpoints() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsProdConfig("https://ssuai.vercel.app").addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/library/seats");
        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("https://ssuai.vercel.app")).isEqualTo("https://ssuai.vercel.app");
        assertThat(config.checkOrigin("https://ssuai-git-main-hoeongj.vercel.app"))
                .isEqualTo("https://ssuai-git-main-hoeongj.vercel.app");
        assertThat(config.checkHttpMethod(HttpMethod.GET)).contains(HttpMethod.GET);
        assertThat(config.checkHeaders(List.of("accept"))).contains("accept");
        assertThat(config.getAllowCredentials()).isFalse();

        assertThat(registry.corsConfiguration("/api/auth/refresh")).isNull();
        assertThat(registry.corsConfiguration("/api/library/reservations/prepare")).isNull();
    }

    @Test
    void beanIsAbsentUnderDevProfile() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withUserConfiguration(WebCorsProdConfig.class)
                .withPropertyValues("ssuai.frontend.origin=https://test.example.com")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WebCorsProdConfig.class));
    }

    @Test
    void beanIsAbsentUnderTestProfile() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
                .withUserConfiguration(WebCorsProdConfig.class)
                .withPropertyValues("ssuai.frontend.origin=https://test.example.com")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WebCorsProdConfig.class));
    }

    @Test
    void contextFailsUnderProdWhenFrontendOriginIsMissing() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(WebCorsProdConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("ssuai.frontend.origin");
                });
    }

    @Test
    void contextFailsUnderProdWhenFrontendOriginIsBlank() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(WebCorsProdConfig.class)
                .withPropertyValues("ssuai.frontend.origin=   ")
                .run(ctx -> assertThat(ctx).hasFailed());
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
