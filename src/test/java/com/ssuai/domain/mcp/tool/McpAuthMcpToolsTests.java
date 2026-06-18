package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpAuthService;
import com.ssuai.domain.auth.mcp.McpAuthSession;
import com.ssuai.domain.auth.mcp.McpAuthSessionId;
import com.ssuai.domain.auth.mcp.McpAuthStateEntry;
import com.ssuai.domain.auth.mcp.McpAuthUrlFactory;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpAuthLogoutResponse;
import com.ssuai.domain.auth.mcp.dto.McpAuthStartResponse;
import com.ssuai.domain.auth.mcp.dto.McpAuthStatusResponse;

class McpAuthMcpToolsTests {

    private McpAuthService mcpAuthService;
    private McpAuthUrlFactory urlFactory;
    private McpAuthHelper mcpAuthHelper;
    private McpAuthMcpTools tools;

    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        mcpAuthService = mock(McpAuthService.class);
        urlFactory = mock(McpAuthUrlFactory.class);
        mcpAuthHelper = mock(McpAuthHelper.class);
        tools = new McpAuthMcpTools(mcpAuthService, urlFactory, mcpAuthHelper);
    }

    // --- get_auth_status ---

    @Test
    void getAuthStatus_unknownSession_returnsAllNotLinked() {
        when(mcpAuthHelper.resolveSession("unknown")).thenReturn(Optional.empty());

        McpAuthStatusResponse resp = tools.getAuthStatus("unknown");

        assertThat(resp.status()).isEqualTo("INVALID_SESSION");
        assertThat(resp.mcpSessionId()).isNull();
        assertThat(resp.providers()).hasSize(McpProviderType.values().length);
        assertThat(resp.providers()).allMatch(p -> !p.linked());
    }

    @Test
    void getAuthStatus_nullSession_returnsAllNotLinked() {
        when(mcpAuthHelper.resolveSession(null)).thenReturn(Optional.empty());

        McpAuthStatusResponse resp = tools.getAuthStatus(null);

        assertThat(resp.status()).isEqualTo("NO_SESSION");
        assertThat(resp.mcpSessionId()).isNull();
        assertThat(resp.providers()).allMatch(p -> !p.linked());
    }

    @Test
    void getAuthStatus_linkedSession_returnsMcpSessionId() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        when(mcpAuthHelper.resolveSession(SESSION_ID.value())).thenReturn(Optional.of(session));

        McpAuthStatusResponse resp = tools.getAuthStatus(SESSION_ID.value());

        assertThat(resp.mcpSessionId()).isEqualTo(SESSION_ID.value());
    }

    @Test
    void getAuthStatus_responseDoesNotContainPrincipalKey() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES,
                java.util.Map.of(McpProviderType.SAINT,
                        new com.ssuai.domain.auth.mcp.McpProviderLink(McpProviderType.SAINT, "20231234", Instant.now())));
        when(mcpAuthHelper.resolveSession(SESSION_ID.value())).thenReturn(Optional.of(session));

        McpAuthStatusResponse resp = tools.getAuthStatus(SESSION_ID.value());

        // The response object contains no principalKey field — verify via toString
        assertThat(resp.toString()).doesNotContain("20231234");
    }

    // --- start_auth ---

    @Test
    void startAuth_withoutSession_createsNewSession() {
        McpAuthSession newSession = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.SAINT, EXPIRES);
        when(mcpAuthService.getOrCreate(null)).thenReturn(newSession);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.SAINT)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.SAINT, "state-token")).thenReturn("https://login.url/saint");

        McpAuthStartResponse resp = tools.startAuth("SAINT", null);

        assertThat(resp.status()).isEqualTo("LOGIN_STARTED");
        assertThat(resp.mcpSessionId()).isEqualTo(SESSION_ID.value());
        assertThat(resp.loginUrl()).isEqualTo("https://login.url/saint");
        assertThat(resp.provider()).isEqualTo("SAINT");
        assertThat(resp.message()).contains("mcp_session_id");
        assertThat(resp.message()).contains("https://login.url/saint");
        assertThat(resp.message()).contains("Do not substitute");
    }

    @Test
    void startAuth_withValidSession_reusesSession() {
        McpAuthSession existing = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.LMS, EXPIRES);
        when(mcpAuthService.getOrCreate(SESSION_ID.value())).thenReturn(existing);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.LMS)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.LMS, "state-token")).thenReturn("https://login.url/lms");

        McpAuthStartResponse resp = tools.startAuth("LMS", SESSION_ID.value());

        assertThat(resp.mcpSessionId()).isEqualTo(SESSION_ID.value());
        verify(mcpAuthService).getOrCreate(SESSION_ID.value());
    }

    @Test
    void startAuth_invalidProvider_returnsError() {
        McpAuthStartResponse resp = tools.startAuth("INVALID", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).getOrCreate(anyString());
    }

    @Test
    void startAuth_lowercaseProvider_isAccepted() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        McpAuthStateEntry state = new McpAuthStateEntry("state-token", SESSION_ID, McpProviderType.LIBRARY, EXPIRES);
        when(mcpAuthService.getOrCreate(null)).thenReturn(session);
        when(mcpAuthService.generateState(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(state);
        when(urlFactory.buildLoginUrl(McpProviderType.LIBRARY, "state-token")).thenReturn("https://login.url/library");

        McpAuthStartResponse resp = tools.startAuth("library", null);

        assertThat(resp.status()).isEqualTo("LOGIN_STARTED");
    }

    // --- logout_provider ---

    @Test
    void logoutProvider_validSession_unlinksProvider() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.of(session));

        McpAuthLogoutResponse resp = tools.logoutProvider("SAINT", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.provider()).isEqualTo("SAINT");
        verify(mcpAuthService).unlinkProvider(SESSION_ID, McpProviderType.SAINT);
    }

    @Test
    void logoutProvider_unknownSession_returnsError() {
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.empty());

        McpAuthLogoutResponse resp = tools.logoutProvider("SAINT", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).unlinkProvider(any(), any());
    }

    @Test
    void logoutProvider_invalidProvider_returnsError() {
        McpAuthLogoutResponse resp = tools.logoutProvider("UNKNOWN", SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).unlinkProvider(any(), any());
    }

    @Test
    void logoutProvider_missingSessionId_returnsError() {
        McpAuthLogoutResponse resp = tools.logoutProvider("SAINT", null);

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).find(any());
    }

    // --- logout_all ---

    @Test
    void logoutAll_validSession_invalidatesSession() {
        McpAuthSession session = new McpAuthSession(SESSION_ID, Instant.now(), EXPIRES, java.util.Map.of());
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.of(session));

        McpAuthLogoutResponse resp = tools.logoutAll(SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.provider()).isNull();
        verify(mcpAuthService).invalidateSession(SESSION_ID);
    }

    @Test
    void logoutAll_unknownSession_returnsError() {
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.empty());

        McpAuthLogoutResponse resp = tools.logoutAll(SESSION_ID.value());

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).invalidateSession(any());
    }

    @Test
    void logoutAll_missingSessionId_returnsError() {
        McpAuthLogoutResponse resp = tools.logoutAll(null);

        assertThat(resp.status()).isEqualTo("ERROR");
        verify(mcpAuthService, never()).find(any());
    }
}
