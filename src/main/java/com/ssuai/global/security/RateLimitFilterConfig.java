package com.ssuai.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RateLimitFilter} as a servlet filter on the two abuse-prone
 * endpoints (security review Wave 3).
 *
 * <p>Mirrors {@link CsrfOriginGuardFilterConfig} / {@code JwtAuthFilterConfig}:
 * the guard runs outside the Spring Security chain (this code base does REST
 * auth via {@code JwtAuthFilter}, not the security filter chain). Registered on
 * {@code /api/*} so {@code /mcp/**} and {@code /actuator/**} never reach it; the
 * filter itself narrows to the exact protected paths via {@code shouldNotFilter}.</p>
 *
 * <p>Ordered to run just after the CSRF Origin guard (HIGHEST_PRECEDENCE + 110)
 * so a forged cross-site request is rejected before we even spend a rate-limit
 * slot on it.</p>
 */
@Configuration
class RateLimitFilterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilterConfig.class);

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitProperties properties,
            ObjectMapper objectMapper) {

        log.info("Per-IP rate limiting active — login={}/window, chat={}/window, "
                        + "confirm={}/window, refresh={}/window, window={}",
                properties.getLoginPerMinute(),
                properties.getChatPerMinute(),
                properties.getConfirmPerMinute(),
                properties.getRefreshPerMinute(),
                properties.getWindow());

        RateLimitFilter filter = RateLimitFilter.forRules(
                properties.getLoginPerMinute(),
                properties.getChatPerMinute(),
                properties.getConfirmPerMinute(),
                properties.getRefreshPerMinute(),
                properties.getWindow(),
                objectMapper);

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        // Run just after CsrfOriginGuardFilter (HIGHEST_PRECEDENCE + 110).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 120);
        return registration;
    }
}
