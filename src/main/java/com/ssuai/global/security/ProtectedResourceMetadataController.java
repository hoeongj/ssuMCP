package com.ssuai.global.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 9728 Protected Resource Metadata endpoint.
 *
 * <p>MCP OAuth flow (how this endpoint fits in):
 * <ol>
 *   <li>Client hits {@code /mcp} without a token.</li>
 *   <li>Server responds 401 with {@code WWW-Authenticate: Bearer realm="ssuMCP",
 *       resource_metadata="<this URL>"}.</li>
 *   <li>Client fetches this document to discover the Authorization Server.</li>
 *   <li>Client completes OAuth 2.1 + PKCE with the AS, obtains a JWT.</li>
 *   <li>Client retries {@code /mcp} with {@code Authorization: Bearer <token>}.</li>
 * </ol>
 *
 * <p>The document's {@code authorization_servers} array tells the client where to
 * fetch tokens. Per the June 2025 MCP spec update, this MUST point to an external
 * identity provider (not the MCP server itself).
 *
 * <p>Only active when {@code SSUAI_OAUTH_RS_ENABLED=true} (same gate as the RS itself).
 * The endpoint is unconditionally permitted in {@link McpOAuthSecurityConfig}
 * ({@code /.well-known/**}) so clients can reach it before auth completes.
 *
 * <p><b>SPIKE:</b> survives the smoke test and into Phase 1 (real implementation).
 */
@RestController
@RequestMapping("/.well-known")
@ConditionalOnProperty(name = "ssuai.mcp.oauth.rs-enabled", havingValue = "true")
class ProtectedResourceMetadataController {

    private final String resourceBaseUrl;
    private final String issuerUri;

    ProtectedResourceMetadataController(
            @Value("${ssuai.mcp.oauth.resource-base-url:https://ssumcp.duckdns.org}") String resourceBaseUrl,
            @Value("${ssuai.mcp.oauth.issuer-uri:}") String issuerUri) {
        this.resourceBaseUrl = resourceBaseUrl;
        this.issuerUri = issuerUri;
    }

    /**
     * Returns the RFC 9728 Protected Resource Metadata document.
     *
     * <p>Key fields:
     * <ul>
     *   <li>{@code resource} — the canonical URL of this resource server.</li>
     *   <li>{@code authorization_servers} — where the client should get tokens
     *       (the managed AS, e.g. Auth0 domain).</li>
     *   <li>{@code bearer_methods_supported} — only {@code header} (no query param).</li>
     * </ul>
     */
    @GetMapping(value = "/oauth-protected-resource", produces = "application/json")
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
            "resource",                  resourceBaseUrl,
            "authorization_servers",     List.of(issuerUri),
            "bearer_methods_supported",  List.of("header"),
            "resource_documentation",    "https://github.com/hoeongj/ssuMCP"
        );
    }
}
