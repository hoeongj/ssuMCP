package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;

class McpAuthHelperTests {

    private static final McpAuthSessionId SESSION_ID =
            new McpAuthSessionId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-05-18T15:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:10:00Z");

    private McpAuthService mcpAuthService;
    private McpAuthUrlFactory urlFactory;
    private HttpServletRequest request;
    private McpAuthHelper helper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mcpAuthService = mock(McpAuthService.class);
        urlFactory = mock(McpAuthUrlFactory.class);
        request = mock(HttpServletRequest.class);
        // Default: no transport session id, no Bearer token — classic mode
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        helper = new McpAuthHelper(mcpAuthService, urlFactory, request);
    }

    @AfterEach
    void tearDown() {
        // Avoid leaking a stubbed JWT into sibling tests via the thread-local context.
        SecurityContextHolder.clearContext();
    }

    /** Places a verified Bearer JWT with the given {@code sub} into the SecurityContext. */
    private static void authenticateWithJwtSub(String sub) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(sub)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void buildAuthRequired_withoutSessionId_createsFreshSession() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.createSession()).thenReturn(session);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.SAINT)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.SAINT, "state-token")).thenReturn("https://login.example/saint");

        McpPrivateToolResponse<Object> response = helper.buildAuthRequired(null, McpProviderType.SAINT);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.mcpSessionId()).isEqualTo(SESSION_ID.value());
        assertThat(response.loginUrl()).isEqualTo("https://login.example/saint");
        assertThat(response.message()).contains("https://login.example/saint");
        assertThat(response.message()).contains("raw loginUrl");
        verify(mcpAuthService).createSession();
    }

    @Test
    void buildAuthRequired_withBlankSessionId_createsFreshSession() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.createSession()).thenReturn(session);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.SAINT)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.SAINT, "state-token")).thenReturn("https://login.example/saint");

        McpPrivateToolResponse<Object> response = helper.buildAuthRequired("", McpProviderType.SAINT);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.mcpSessionId()).isEqualTo(SESSION_ID.value());
        verify(mcpAuthService).createSession();
    }

    @Test
    void buildAuthRequired_withUnknownNonBlankSessionIdDoesNotCreateSession() {
        // Security scenario a tester misread as an auth bypass: unauthenticated (no JWT in
        // SecurityContext, no Mcp-Session-Id header) + a fake/unknown explicit mcp_session_id.
        // resolveSession must find nothing, and the private-tool response must be a clean
        // INVALID_SESSION with no leaked data and no loginUrl.
        String unknownSessionId = "ffffffff-1111-2222-3333-444444444444";
        when(mcpAuthService.find(unknownSessionId)).thenReturn(Optional.empty());

        assertThat(helper.resolveSession(unknownSessionId)).isEmpty();

        McpPrivateToolResponse<Object> response = helper.buildAuthRequired(unknownSessionId, McpProviderType.SAINT);

        assertThat(response.status()).isEqualTo("INVALID_SESSION");
        assertThat(response.mcpSessionId()).isEqualTo(unknownSessionId);
        assertThat(response.loginUrl()).isNull();
        assertThat(response.data()).isNull();
        verify(mcpAuthService, never()).createSession();
        verify(mcpAuthService, never()).generateState(any(), any());
    }

    @Test
    void resolveSession_fallsBackToTransportId_whenOpaqueIdMissing() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("transport-123");
        when(mcpAuthService.findByTransportId("transport-123")).thenReturn(Optional.of(session));

        Optional<McpAuthSession> resolved = helper.resolveSession(null);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().id()).isEqualTo(SESSION_ID);
    }

    @Test
    void resolveSession_transportFoundWithoutOauthSub_doesNotBindOrVerify() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("transport-123");
        when(mcpAuthService.findByTransportId("transport-123")).thenReturn(Optional.of(session));
        // No JWT in SecurityContext → currentOauthSub() is null, so the ownership guard is
        // skipped entirely and the session resolves unchanged (classic mode behavior).
        Optional<McpAuthSession> resolved = helper.resolveSession(null);

        assertThat(resolved).isPresent();
        verify(mcpAuthService, never()).bindOrVerifyOauthSubject(any(), any());
        verify(mcpAuthService, never()).bindOauthSubject(any(), any());
    }

    @Test
    void resolveSession_transportTierOauthSubMismatch_deniesAndReturnsEmpty() {
        // Tier-2 ownership guard: a stolen transport id resolves a session bound to sub-A while
        // the request presents JWT sub-B. bindOrVerify returns false → must NOT return the
        // victim's session. With no opaque arg, the resolution falls through to empty.
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("transport-123");
        when(mcpAuthService.findByTransportId("transport-123")).thenReturn(Optional.of(session));
        when(mcpAuthService.bindOrVerifyOauthSubject(SESSION_ID, "sub-B")).thenReturn(false);
        authenticateWithJwtSub("sub-B");

        Optional<McpAuthSession> resolved = helper.resolveSession(null);

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveSession_opaqueTierOauthSubMismatch_deniesAndDoesNotBindTransport() {
        // Tier-3 ownership guard: a stolen opaque mcp_session_id resolves a session bound to
        // sub-A while the request presents JWT sub-B. Must return empty AND must not
        // opportunistically bind the transport id to a session the caller does not own.
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        when(request.getHeader("Mcp-Session-Id")).thenReturn("transport-123");
        when(mcpAuthService.findByTransportId("transport-123")).thenReturn(Optional.empty());
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.of(session));
        when(mcpAuthService.bindOrVerifyOauthSubject(SESSION_ID, "sub-B")).thenReturn(false);
        authenticateWithJwtSub("sub-B");

        Optional<McpAuthSession> resolved = helper.resolveSession(SESSION_ID.value());

        assertThat(resolved).isEmpty();
        verify(mcpAuthService, never()).bindTransportId(any(), any());
    }

    @Test
    void buildAuthRequired_withExistingSessionGeneratesLoginUrl() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, NOW, EXPIRES, Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.LMS, EXPIRES);
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.of(session));
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.LMS)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.LMS, "state-token")).thenReturn("https://login.example/lms");

        McpPrivateToolResponse<Object> response = helper.buildAuthRequired(SESSION_ID.value(), McpProviderType.LMS);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.loginUrl()).isEqualTo("https://login.example/lms");
        verify(mcpAuthService, never()).createSession();
    }
}
