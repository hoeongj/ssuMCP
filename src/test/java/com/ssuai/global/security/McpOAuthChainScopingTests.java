package com.ssuai.global.security;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.repository.StudentRepository;
import com.ssuai.global.auth.JwtProvider;

/**
 * Regression guard for the 2026-07-03 prod incident (ADR 0074): with the OAuth
 * resource server enabled ({@code rs-enabled=true}, exactly like prod), Spring
 * Security's {@code BearerTokenAuthenticationFilter} must only ever see the MCP
 * surface. An unscoped chain made it intercept the web app's own HS256 session
 * JWTs on {@code /api/**} and 401 them as "not an Auth0 token", killing every
 * logged-in web feature — while the whole test suite stayed green because tests
 * ran with {@code rs-enabled=false}.
 *
 * <p>This class boots the context in the prod-equivalent OAuth mode against a
 * WireMock-served OIDC discovery document, then asserts both sides of the chain
 * split: web-session JWTs keep working on {@code /api/**}, and the MCP challenge
 * contract (401 + RFC 9728 {@code resource_metadata}) keeps working on
 * {@code /mcp}.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class McpOAuthChainScopingTests {

    private static final WireMockServer AUTH_SERVER =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final String TEST_STUDENT_ID = "99990001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @DynamicPropertySource
    static void oauthResourceServerProps(DynamicPropertyRegistry registry) {
        AUTH_SERVER.start();
        String issuer = AUTH_SERVER.baseUrl() + "/";
        AUTH_SERVER.stubFor(WireMock.get(urlEqualTo("/.well-known/openid-configuration")).willReturn(okJson("""
                {
                  "issuer": "%s",
                  "authorization_endpoint": "%sauthorize",
                  "token_endpoint": "%soauth/token",
                  "jwks_uri": "%s.well-known/jwks.json",
                  "response_types_supported": ["code"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """.formatted(issuer, issuer, issuer, issuer))));
        // JwtDecoders.fromIssuerLocation inspects the JWKS at startup to infer the
        // signing algorithm — an empty key set fails context boot, so serve a real
        // (throwaway) RS256 public key. No token in these tests is ever signed by it.
        AUTH_SERVER.stubFor(WireMock.get(urlEqualTo("/.well-known/jwks.json"))
                .willReturn(okJson(throwawayJwks())));

        registry.add("ssuai.mcp.oauth.rs-enabled", () -> "true");
        registry.add("ssuai.mcp.oauth.issuer-uri", () -> issuer);
        registry.add("ssuai.mcp.oauth.audience", () -> "https://ssumcp.duckdns.org/mcp");
    }

    private static String throwawayJwks() {
        try {
            RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("chain-scoping-test").generate();
            return new JWKSet(rsaKey.toPublicJWK()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate test JWKS", e);
        }
    }

    @AfterAll
    static void shutDownAuthServer() {
        AUTH_SERVER.stop();
    }

    @BeforeEach
    void seedStudent() {
        if (studentRepository.findById(TEST_STUDENT_ID).isEmpty()) {
            studentRepository.save(new Student(
                    TEST_STUDENT_ID, "체인분리테스트", "컴퓨터학부", "재학", Instant.now()));
        }
    }

    /**
     * The exact prod failure: a perfectly valid ssuAI web access JWT on
     * {@code /api/auth/me}. Before the chain split this returned the MCP 401
     * challenge; it must reach {@code JwtAuthFilter} + controller and succeed.
     */
    @Test
    void webSessionJwtStillWorksOnApiWithOAuthEnabled() throws Exception {
        Student student = studentRepository.findById(TEST_STUDENT_ID).orElseThrow();
        String webAccessJwt = jwtProvider.issueAccess(student);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + webAccessJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentId").value(TEST_STUDENT_ID));
    }

    /**
     * A garbage Bearer on a web API path must produce the web-layer 401
     * envelope (ApiResponse + traceId), not the MCP RFC 9728 challenge —
     * proving BearerTokenAuthenticationFilter no longer runs there.
     */
    @Test
    void invalidBearerOnWebApiGetsWebEnvelopeNotMcpChallenge() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not-an-auth0-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    /** The MCP OAuth challenge contract must survive the chain split intact. */
    @Test
    void mcpSurfaceStillChallengesInvalidBearerTokens() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer not-an-auth0-token")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("resource_metadata")))
                .andExpect(header().string("WWW-Authenticate", not(containsString("error=\"insufficient_scope\""))));
    }

    /**
     * The RFC 9728 PRM document is served by a filter registered inside the
     * OAuth chain — {@code /.well-known/**} must stay matched by that chain
     * or ChatGPT/Claude lose Authorization Server discovery (TROUBLESHOOTING
     * 2026-06-18).
     */
    @Test
    void protectedResourceMetadataStillAdvertisesAuthorizationServer() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_servers[0]").value(AUTH_SERVER.baseUrl() + "/"));
    }
}
