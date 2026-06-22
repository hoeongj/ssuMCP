package com.ssuai.global.security;

import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Registers {@link CsrfOriginGuardFilter} as a servlet filter on {@code /api/*}.
 *
 * <p>The guard runs outside the Spring Security chain (this code base handles
 * REST auth via {@code JwtAuthFilter}, not the security filter chain — see
 * {@code JwtAuthFilterConfig}), mirroring that registration style. The allowed
 * origin set is built once from {@code ssuai.frontend.origin} (the same value
 * the CORS layer pins); in non-prod {@code http://localhost:3000} is added so
 * local frontend dev passes. A blank {@code ssuai.frontend.origin} is skipped
 * rather than added as an empty entry.</p>
 *
 * <p>Ordered to run just after {@link com.ssuai.global.auth.JwtAuthFilter} so a
 * forged cross-site request is rejected before controller logic, while keeping
 * the (cheap, attribute-only) JWT parse ahead of it for consistent ordering.</p>
 */
@Configuration
class CsrfOriginGuardFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(CsrfOriginGuardFilterConfig.class);

    @Bean
    FilterRegistrationBean<CsrfOriginGuardFilter> csrfOriginGuardFilterRegistration(
            @Value("${ssuai.frontend.origin:}") String frontendOrigin,
            Environment environment,
            ObjectMapper objectMapper) {

        Set<String> allowedOrigins = buildAllowedOrigins(frontendOrigin, environment);
        log.info("CSRF Origin/Referer guard active on /api/* — allowed origins: {}", allowedOrigins);

        FilterRegistrationBean<CsrfOriginGuardFilter> registration =
                new FilterRegistrationBean<>(new CsrfOriginGuardFilter(allowedOrigins, objectMapper));
        registration.addUrlPatterns("/api/*");
        // Run just after JwtAuthFilter (HIGHEST_PRECEDENCE + 100).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 110);
        return registration;
    }

    private static Set<String> buildAllowedOrigins(String frontendOrigin, Environment environment) {
        Set<String> origins = new LinkedHashSet<>();
        if (frontendOrigin != null && !frontendOrigin.isBlank()) {
            origins.add(frontendOrigin.strip());
        }
        if (!isProd(environment)) {
            origins.add("http://localhost:3000");
        }
        return origins;
    }

    private static boolean isProd(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
