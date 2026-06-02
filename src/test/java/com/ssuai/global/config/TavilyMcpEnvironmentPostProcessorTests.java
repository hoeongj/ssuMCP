package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class TavilyMcpEnvironmentPostProcessorTests {

    private static final String SPRING_TAVILY_URL =
            "spring.ai.mcp.client.streamable-http.connections.tavily.url";

    private final TavilyMcpEnvironmentPostProcessor postProcessor =
            new TavilyMcpEnvironmentPostProcessor();

    @Test
    void doesNotRegisterTavilyConnectionWhenUrlIsBlank() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SSUAI_TAVILY_MCP_URL", "   ");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(SPRING_TAVILY_URL)).isNull();
    }

    @Test
    void mapsSsuaiTavilyUrlToSpringAiConnectionProperty() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SSUAI_TAVILY_MCP_URL", " https://tavily.test/mcp ");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(SPRING_TAVILY_URL))
                .isEqualTo("https://tavily.test/mcp");
    }
}
