package com.ssuai.global.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
class JwtAuthFilterConfig {

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtProvider jwtProvider) {
        FilterRegistrationBean<JwtAuthFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthFilter(jwtProvider));
        registration.addUrlPatterns("/api/*");
        // Run before any application filter that might want to read the
        // attributes (current code base only has TraceIdFilter, which doesn't
        // touch them, but make ordering explicit so future filters compose
        // predictably).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}
