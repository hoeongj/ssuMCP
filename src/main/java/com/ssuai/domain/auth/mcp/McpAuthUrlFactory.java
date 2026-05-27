package com.ssuai.domain.auth.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.AuthProperties;

/**
 * Builds absolute URLs for the MCP auth login flow.
 *
 * <p>The login URL is passed to the external MCP client in {@code start_auth} output.
 * The client pastes it into a browser; the browser visits the URL and completes the
 * upstream SSO. The state token is embedded in the URL as a query param — it is NOT
 * a separate JSON field so it is not accidentally logged or displayed.
 */
@Component
public class McpAuthUrlFactory {

    private final AuthProperties authProperties;

    public McpAuthUrlFactory(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /**
     * Returns the full login-initiation URL for the given provider and one-time state token.
     * The client visits this URL in a browser to complete authentication.
     */
    public String buildLoginUrl(McpProviderType provider, String state) {
        String path = switch (provider) {
            case SAINT -> "/api/mcp/auth/saint/start";
            case LMS -> "/api/mcp/auth/lms/start";
            case LIBRARY -> "/api/mcp/auth/library/start";
        };
        return authProperties.getMcpApiBaseUrl() + path
                + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    /**
     * Returns the absolute callback URL that the upstream SSO should redirect back to.
     * The state param is appended so the callback controller can correlate the flow.
     */
    public String buildCallbackUrl(McpProviderType provider, String state) {
        String path = switch (provider) {
            case SAINT -> "/api/mcp/auth/saint/callback";
            case LMS -> "/api/mcp/auth/lms/callback";
            case LIBRARY -> "/api/mcp/auth/library/callback";
        };
        return authProperties.getMcpApiBaseUrl() + path
                + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }
}
