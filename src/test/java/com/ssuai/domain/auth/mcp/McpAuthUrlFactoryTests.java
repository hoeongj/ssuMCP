package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.AuthProperties;

class McpAuthUrlFactoryTests {

    private static McpAuthUrlFactory factory(String baseUrl) {
        AuthProperties props = new AuthProperties();
        props.setApiBaseUrl(baseUrl);
        return new McpAuthUrlFactory(props);
    }

    private static McpAuthUrlFactory factory(String baseUrl, String mcpBaseUrl) {
        AuthProperties props = new AuthProperties();
        props.setApiBaseUrl(baseUrl);
        props.setMcpApiBaseUrl(mcpBaseUrl);
        return new McpAuthUrlFactory(props);
    }

    @Test
    void buildLoginUrlSaintIncludesCorrectPath() {
        McpAuthUrlFactory factory = factory("https://api.example.com");
        String url = factory.buildLoginUrl(McpProviderType.SAINT, "abc123");
        assertThat(url).startsWith("https://api.example.com/api/mcp/auth/saint/start?state=");
        assertThat(url).contains("abc123");
    }

    @Test
    void buildLoginUrlLmsIncludesCorrectPath() {
        McpAuthUrlFactory factory = factory("https://api.example.com");
        String url = factory.buildLoginUrl(McpProviderType.LMS, "abc123");
        assertThat(url).startsWith("https://api.example.com/api/mcp/auth/lms/start?state=");
    }

    @Test
    void buildLoginUrlLibraryIncludesCorrectPath() {
        McpAuthUrlFactory factory = factory("https://api.example.com");
        String url = factory.buildLoginUrl(McpProviderType.LIBRARY, "abc123");
        assertThat(url).startsWith("https://api.example.com/api/mcp/auth/library/start?state=");
    }

    @Test
    void buildCallbackUrlSaintIncludesCorrectPath() {
        McpAuthUrlFactory factory = factory("https://api.example.com");
        String url = factory.buildCallbackUrl(McpProviderType.SAINT, "xyz789");
        assertThat(url).startsWith("https://api.example.com/api/mcp/auth/saint/callback?state=");
        assertThat(url).contains("xyz789");
    }

    @Test
    void buildLoginUrlUsesMcpBaseUrlWhenConfigured() {
        McpAuthUrlFactory factory = factory("https://web.example.com", "https://mcp.example.com");
        String url = factory.buildLoginUrl(McpProviderType.SAINT, "abc123");
        assertThat(url).startsWith("https://mcp.example.com/api/mcp/auth/saint/start?state=");
    }

    @Test
    void buildCallbackUrlFallsBackToApiBaseUrlWhenMcpBaseUrlIsBlank() {
        McpAuthUrlFactory factory = factory("https://web.example.com", " ");
        String url = factory.buildCallbackUrl(McpProviderType.SAINT, "xyz789");
        assertThat(url).startsWith("https://web.example.com/api/mcp/auth/saint/callback?state=");
    }

    @Test
    void stateWithSpecialCharsIsUrlEncoded() {
        McpAuthUrlFactory factory = factory("https://api.example.com");
        // UUID hyphens do not need encoding, but test with chars that do
        String url = factory.buildLoginUrl(McpProviderType.SAINT, "a b+c=d&e");
        assertThat(url).doesNotContain(" ");
        assertThat(url).doesNotContain("&e");
        assertThat(url).contains("a+b");
    }

    @Test
    void differentProvidersDifferentPaths() {
        McpAuthUrlFactory factory = factory("https://api.example.com");
        String saint = factory.buildLoginUrl(McpProviderType.SAINT, "s");
        String lms = factory.buildLoginUrl(McpProviderType.LMS, "s");
        String library = factory.buildLoginUrl(McpProviderType.LIBRARY, "s");
        assertThat(saint).isNotEqualTo(lms);
        assertThat(lms).isNotEqualTo(library);
    }
}
