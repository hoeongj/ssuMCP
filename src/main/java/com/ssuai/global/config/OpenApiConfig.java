package com.ssuai.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "ssuAI Backend API",
                version = "0.0.1",
                description = "Read-only REST API for Soongsil University public campus data. See docs/product.md."
        )
)
public class OpenApiConfig {
}
