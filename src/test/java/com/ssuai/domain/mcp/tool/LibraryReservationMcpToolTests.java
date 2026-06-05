package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;

class LibraryReservationMcpToolTests {

    private static final Instant EXPIRES = Instant.parse("2026-06-05T10:05:00Z");

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private McpAuthHelper authHelper;
    private LibraryReservationMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibraryReservationMcpTool(actionService, sessionStore, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<String> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<String> response =
                tool.prepareReserveLibrarySeat(null, "2F", "101");

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.provider()).isEqualTo("LIBRARY");
        verifyNoInteractions(sessionStore);
        verifyNoInteractions(actionService);
    }
}
