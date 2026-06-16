package com.ssuai.global.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP OAuth 2.1 Resource Server security configuration.
 *
 * <p><b>SPIKE (Step 0 gate):</b> This class is a throwaway feasibility probe that
 * activates only when {@code SSUAI_OAUTH_RS_ENABLED=true}. It will be replaced by
 * the Phase 1 production implementation after the OAuth round-trip smoke test passes.
 *
 * <h2>Why OAuth?</h2>
 * The existing opaque {@code mcp_session_id} parameter requires the LLM to carry a
 * stable identifier across turns. ChatGPT drops this between turns, creating a new
 * orphan session B on each {@code start_auth} re-call → the user's login (on session A)
 * is never found → LIBRARY=false infinite loop.
 *
 * <p>MCP spec (2025-06): remote MCP identity MUST be derived from the authorization
 * credential's {@code sub} claim and MUST NOT be tied to session id alone.
 * OAuth 2.1 Bearer tokens carry the {@code sub} at the HTTP layer, below LLM reasoning
 * — it cannot be dropped between turns.
 *
 * <h2>Mode switch (property-gated)</h2>
 * <ul>
 *   <li>{@code SSUAI_OAUTH_RS_ENABLED=false} (default): Spring Security is configured
 *       but permits all requests — existing behavior is fully preserved.</li>
 *   <li>{@code SSUAI_OAUTH_RS_ENABLED=true}: OAuth2 RS mode; {@code /mcp/**} requires
 *       a valid Bearer JWT from the managed Authorization Server.</li>
 * </ul>
 *
 * <h2>Why a single bean rather than two @ConditionalOnProperty configs?</h2>
 * Having exactly one {@code SecurityFilterChain} bean prevents Spring Boot's
 * {@code SpringBootWebSecurityConfiguration} default chain (HTTP Basic, block all)
 * from activating in either mode. Two separate conditional beans risk a gap where
 * neither condition matches and the default chain takes over.
 *
 * @see ProtectedResourceMetadataController RFC 9728 discovery endpoint
 */
@Configuration
@EnableWebSecurity
class McpOAuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthSecurityConfig.class);

    /** Set SSUAI_OAUTH_RS_ENABLED=true in prod k8s ConfigMap to activate OAuth RS mode. */
    @Value("${ssuai.mcp.oauth.rs-enabled:false}")
    private boolean oauthRsEnabled;

    /** Managed Authorization Server issuer URI. Fetched via OIDC discovery on startup. */
    @Value("${ssuai.mcp.oauth.issuer-uri:}")
    private String issuerUri;

    /** Public base URL of this resource server — used in WWW-Authenticate header. */
    @Value("${ssuai.mcp.oauth.resource-base-url:https://ssumcp.duckdns.org}")
    private String resourceBaseUrl;

    @Bean
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            // Reuse the existing CORS configuration from WebCorsConfig / WebCorsProdConfig
            // (registered via WebMvcConfigurer#addCorsMappings, not Spring Security CORS DSL).
            // Calling cors(withDefaults()) tells Spring Security to delegate to that config.
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (oauthRsEnabled) {
            log.info("MCP OAuth RS activated — issuer: {}, resource: {}", issuerUri, resourceBaseUrl);

            // JwtDecoders.fromIssuerLocation performs OIDC discovery (GET <issuer>/.well-known/*)
            // on startup. This is expected; if the AS is unreachable, startup fails fast.
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

            String metadataUrl = resourceBaseUrl + "/.well-known/oauth-protected-resource";

            http
                .authorizeHttpRequests(auth -> auth
                    // RFC 9728 PRM endpoint — must be reachable before auth completes
                    .requestMatchers("/.well-known/**").permitAll()
                    // MCP school-credential login callbacks are browser redirects (no Bearer)
                    .requestMatchers("/api/mcp/auth/**").permitAll()
                    // k8s liveness/readiness probes — unauthenticated
                    .requestMatchers("/actuator/**").permitAll()
                    // The MCP Streamable HTTP endpoint: requires a valid Bearer JWT
                    .requestMatchers("/mcp", "/mcp/**").authenticated()
                    // REST API, Swagger, chat UI — unchanged (handled by existing JwtAuthFilter)
                    .anyRequest().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                    .jwt(jwt -> jwt.decoder(decoder))
                    // On 401: emit WWW-Authenticate with resource_metadata URL per RFC 9728 §5.
                    // MCP clients (ChatGPT / Claude) follow this URL to discover the AS.
                    .authenticationEntryPoint((req, res, ex) -> {
                        res.setStatus(HttpStatus.UNAUTHORIZED.value());
                        res.setHeader("WWW-Authenticate",
                                "Bearer realm=\"ssuMCP\", resource_metadata=\"" + metadataUrl + "\"");
                        res.setContentType("application/json;charset=UTF-8");
                        res.getWriter().write(
                                "{\"error\":\"unauthorized\",\"message\":\"Bearer token required. "
                                + "Discover the Authorization Server at: " + metadataUrl + "\"}");
                    })
                );
        } else {
            // Passthrough mode: Spring Security is on the classpath but imposes no restrictions.
            // This preserves all existing behavior (tests, dev, prod before OAuth is enabled).
            log.debug("MCP OAuth RS disabled (SSUAI_OAUTH_RS_ENABLED not set to true) — all requests permitted");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }
}
