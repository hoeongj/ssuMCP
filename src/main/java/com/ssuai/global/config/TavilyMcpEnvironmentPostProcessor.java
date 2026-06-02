package com.ssuai.global.config;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Adds the optional Tavily MCP connection only when its URL is configured.
 *
 * <p>Spring AI validates every declared Streamable HTTP connection during
 * context creation, so an empty {@code tavily.url} in {@code application.yml}
 * prevents startup. Keeping the optional connection out of static YAML lets
 * local/dev deployments run without Tavily while still supporting the shorter
 * {@code SSUAI_TAVILY_MCP_URL} env var when it is present.
 */
public class TavilyMcpEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "ssuaiTavilyMcpConnection";
    private static final String SSUAI_TAVILY_URL = "SSUAI_TAVILY_MCP_URL";
    private static final String SPRING_TAVILY_URL =
            "spring.ai.mcp.client.streamable-http.connections.tavily.url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (StringUtils.hasText(environment.getProperty(SPRING_TAVILY_URL))) {
            return;
        }
        String tavilyUrl = environment.getProperty(SSUAI_TAVILY_URL);
        if (!StringUtils.hasText(tavilyUrl)) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                SOURCE_NAME,
                Map.of(SPRING_TAVILY_URL, tavilyUrl.trim())
        ));
    }
}
