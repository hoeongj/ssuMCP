package com.ssuai.global.security;

import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP OAuth 2.1 Resource Server — opt-in, property-gated.
 *
 * <h2>Opt-in 2-mode design (ADR 0036)</h2>
 * <p>This bean is ALWAYS loaded (no class-level {@code @ConditionalOnProperty}) so that
 * Spring Boot's default security auto-config (which locks all endpoints) is prevented
 * when {@code spring-boot-starter-oauth2-resource-server} is on the classpath.
 * JWT resource server setup is applied <em>conditionally at runtime</em> inside the bean
 * method based on the {@code rs-enabled} flag and a non-blank {@code issuer-uri}.</p>
 *
 * <ul>
 *   <li><b>Off (default, rs-enabled=false or issuer-uri blank)</b>: a permissive
 *       {@code SecurityFilterChain} is created with no JWT validation.
 *       All existing behaviour is fully preserved.</li>
 *   <li><b>On (rs-enabled=true, issuer-uri set)</b>: opt-in OAuth mode.
 *       {@code permitAll()} on {@code /mcp/**} means a missing token is not an instant 401 —
 *       the request proceeds as anonymous (original mode).
 *       Spring's {@code BearerTokenAuthenticationFilter} is independent of authorization
 *       rules: if a Bearer token is present it is validated and SecurityContext is populated;
 *       if absent the request continues as anonymous; if the token is invalid or expired
 *       it returns 401 (correct — the client re-auths). This is the "opt-in coexistence"
 *       pattern from the MCP OAuth 2.1 spec (2026) and RFC 9728.</li>
 * </ul>
 *
 * <h2>Why permitAll() and not authenticated()?</h2>
 * <p>Public tools (meal, library search, notices, facilities) must remain zero-auth.
 * Forcing authentication breaks them for users who do not want OAuth. Private tools
 * perform their own auth check via {@code McpAuthHelper.principalKey()} — that check
 * also reads the {@code sub} from SecurityContext when a JWT is present (ADR 0036 §1C).</p>
 *
 * <h2>Audience validation</h2>
 * <p>The spike omitted audience validation; Phase 1 enforces it via
 * {@code JwtClaimValidator<List<String>>} so tokens issued to a different
 * audience (a different resource server) are rejected.</p>
 *
 * <h2>RFC 9728 Protected Resource Metadata — the {@code authorization_servers} fix</h2>
 * <p>The MCP OAuth discovery flow is: (1) client hits {@code /mcp} with a missing/expired
 * Bearer token; (2) server answers 401 with
 * {@code WWW-Authenticate: Bearer resource_metadata="<PRM URL>"}; (3) client fetches
 * {@code /.well-known/oauth-protected-resource} to discover the Authorization Server from
 * its {@code authorization_servers} array; (4) client runs OAuth 2.1 + PKCE against that AS
 * and retries {@code /mcp} with the JWT.</p>
 * <p><b>Spring Security 7 serves this document itself.</b> Configuring
 * {@code oauth2ResourceServer} auto-registers {@code OAuth2ProtectedResourceMetadataFilter},
 * a servlet filter that responds at {@code /.well-known/oauth-protected-resource} <em>before</em>
 * the DispatcherServlet — so a hand-written {@code @GetMapping} for the same path is silently
 * shadowed and never runs. The generated document omits {@code authorization_servers} (the
 * framework cannot know the external AS), which left ChatGPT unable to discover Auth0 and stuck
 * looping on {@code start_auth}. We inject the managed issuer via
 * {@link #authorizationServersCustomizer(String)} instead of a controller (TROUBLESHOOTING 2026-06-18).</p>
 */
@Configuration
@EnableWebSecurity
class McpOAuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthSecurityConfig.class);

    @Value("${ssuai.mcp.oauth.rs-enabled:false}")
    private boolean rsEnabled;

    /** Managed Authorization Server issuer URI (e.g. https://<tenant>.auth0.com/). */
    @Value("${ssuai.mcp.oauth.issuer-uri:}")
    private String issuerUri;

    /** Expected audience claim value — must match this resource server's identifier. */
    @Value("${ssuai.mcp.oauth.audience:}")
    private String audience;

    /** Public base URL of this resource server, used in WWW-Authenticate header. */
    @Value("${ssuai.mcp.oauth.resource-base-url:https://ssumcp.duckdns.org}")
    private String resourceBaseUrl;

    @Bean
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // RFC 9728 PRM and other well-known docs — must be reachable before auth
                .requestMatchers("/.well-known/**").permitAll()
                // MCP school-credential login callbacks are browser redirects, no Bearer
                .requestMatchers("/api/mcp/auth/**").permitAll()
                // k8s probes
                .requestMatchers("/actuator/**").permitAll()
                // MCP Streamable HTTP: permitAll so missing tokens don't 401 anonymous mode.
                // BearerTokenAuthenticationFilter validates tokens if present regardless.
                .requestMatchers("/mcp", "/mcp/**").permitAll()
                // REST API, chat, Swagger — existing auth handled elsewhere
                .anyRequest().permitAll()
            );

        if (rsEnabled && issuerUri != null && !issuerUri.isBlank()) {
            log.info("MCP OAuth RS activated — issuer: {}, audience: {}, resource: {}",
                    issuerUri, audience, resourceBaseUrl);
            NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
            decoder.setJwtValidator(buildValidator());
            String metadataUrl = resourceBaseUrl + "/.well-known/oauth-protected-resource";
            http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(decoder))
                // RFC 9728: add the managed AS issuer to the framework-generated PRM document
                // so MCP clients can discover Auth0 (see class javadoc — the generated default
                // omits authorization_servers and shadows any custom @GetMapping).
                .protectedResourceMetadata(metadata -> metadata
                        .protectedResourceMetadataCustomizer(authorizationServersCustomizer(issuerUri)))
                // On invalid/expired token: 401 + WWW-Authenticate with resource_metadata URL.
                // MCP clients (ChatGPT / Claude) follow this URL to discover the AS.
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setHeader("WWW-Authenticate",
                            "Bearer realm=\"ssuMCP\", resource_metadata=\"" + metadataUrl + "\"");
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write(objectMapper.writeValueAsString(java.util.Map.of(
                            "error", "unauthorized",
                            "message", "Bearer token required or token invalid. "
                                    + "Discover the Authorization Server at: " + metadataUrl)));
                })
            );
        } else {
            log.debug("MCP OAuth RS disabled — classic mode (rs-enabled=false or issuer-uri not set)");
        }

        return http.build();
    }

    /**
     * Combines issuer validation (signature + exp/nbf) with audience validation.
     * Audience claim must contain {@link #audience} — this prevents a token issued
     * for a different resource server from being accepted here.
     */
    private OAuth2TokenValidator<Jwt> buildValidator() {
        // Issuer validator: verifies signature, iss, exp, nbf via OIDC discovery
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuerUri);

        // Audience validator: aud must contain our resource server identifier
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                aud -> aud != null && aud.contains(audience)
        );

        return token -> {
            OAuth2TokenValidatorResult issuerResult = issuerValidator.validate(token);
            if (issuerResult.hasErrors()) {
                return issuerResult;
            }
            return audienceValidator.validate(token);
        };
    }

    /**
     * Adds the managed Authorization Server issuer to the RFC 9728 Protected Resource
     * Metadata document. Spring Security 7's {@code OAuth2ProtectedResourceMetadataFilter}
     * generates the base document (resource, bearer_methods_supported, …) but cannot know
     * about the external AS; without {@code authorization_servers} an MCP client has no way
     * to find where to obtain a token and loops on re-authentication.
     *
     * <p>Extracted as a static method so it is unit-testable without booting the servlet
     * filter or performing OIDC discovery: build a metadata document, apply this consumer,
     * and assert the {@code authorization_servers} claim.
     */
    static Consumer<OAuth2ProtectedResourceMetadata.Builder> authorizationServersCustomizer(String issuerUri) {
        return builder -> builder.authorizationServer(issuerUri);
    }
}
