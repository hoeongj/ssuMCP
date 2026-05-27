package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
    void apiCorsMappingAllowsGetPostAndOptions() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsProdConfig("https://test.example.com").addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowedMethods()).contains("GET", "POST", "OPTIONS");
    }

    @Test
    void apiCorsMappingAllowsCredentialsUnderProd() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebCorsProdConfig("https://test.example.com").addCorsMappings(registry);

        CorsConfiguration config = registry.corsConfiguration("/api/**");
        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isTrue();
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
    }
}
