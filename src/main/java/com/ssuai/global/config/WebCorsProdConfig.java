package com.ssuai.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("prod")
public class WebCorsProdConfig implements WebMvcConfigurer {

    private final String frontendOrigin;

    public WebCorsProdConfig(@Value("${ssuai.frontend.origin:}") String frontendOrigin) {
        if (frontendOrigin == null || frontendOrigin.isBlank()) {
            throw new IllegalStateException(
                    "Production profile requires ssuai.frontend.origin "
                            + "(env: SSUAI_FRONTEND_ORIGIN) to be set. "
                            + "Wildcard CORS is not allowed in prod — see docs/security.md §8.");
        }
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        ApiCorsDefaults.register(registry, frontendOrigin);
    }
}
