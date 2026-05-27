package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private McpAuthHelper helper;

    @BeforeEach
    void setUp() {
        mcpAuthService = mock(McpAuthService.class);
        urlFactory = mock(McpAuthUrlFactory.class);
        helper = new McpAuthHelper(mcpAuthService, urlFactory);
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
        String unknownSessionId = "ffffffff-1111-2222-3333-444444444444";
        when(mcpAuthService.find(unknownSessionId)).thenReturn(Optional.empty());

        McpPrivateToolResponse<Object> response = helper.buildAuthRequired(unknownSessionId, McpProviderType.SAINT);

        assertThat(response.status()).isEqualTo("INVALID_SESSION");
        assertThat(response.mcpSessionId()).isEqualTo(unknownSessionId);
        assertThat(response.loginUrl()).isNull();
        verify(mcpAuthService, never()).createSession();
        verify(mcpAuthService, never()).generateState(any(), any());
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
