package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;

/**
 * Guards the RFC 9728 {@code authorization_servers} fix (G4, TROUBLESHOOTING 2026-06-18).
 *
 * <p>Spring Security 7 generates the Protected Resource Metadata document via a servlet
 * filter; the only thing we add is the managed AS issuer. This test verifies that the
 * extracted customizer actually injects {@code authorization_servers} — the missing field
 * that left ChatGPT unable to discover Auth0. It deliberately exercises the customizer in
 * isolation (no servlet filter, no OIDC discovery) so it stays a fast, deterministic
 * regression guard; the end-to-end wiring is verified by curling the live PRM endpoint
 * after deploy.
 */
class McpOAuthSecurityConfigTests {

    @Test
    void customizerInjectsManagedAuthorizationServerIssuer() {
        String issuer = "https://dev-q784z2phbyf5gzj7.us.auth0.com/";

        Consumer<OAuth2ProtectedResourceMetadata.Builder> customizer =
                McpOAuthSecurityConfig.authorizationServersCustomizer(issuer);

        OAuth2ProtectedResourceMetadata.Builder builder =
                OAuth2ProtectedResourceMetadata.builder().resource("https://ssumcp.duckdns.org");
        customizer.accept(builder);
        OAuth2ProtectedResourceMetadata metadata = builder.build();

        assertThat(metadata.getAuthorizationServers())
                .extracting(URL::toString)
                .containsExactly(issuer);
    }
}
