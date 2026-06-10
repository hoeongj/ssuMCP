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
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;

class LibraryReservationMcpToolTests {

    private static final Instant EXPIRES = Instant.parse("2026-06-05T10:05:00Z");
    private static final String SESSION_ID = "session-abc";
    private static final String SESSION_KEY = "key-xyz";
    private static final String TOKEN = "pyxis-tok";

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private McpAuthHelper authHelper;
    private LibraryReservationMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibraryReservationMcpTool(
                actionService, sessionStore, reservationConnector, new LibrarySeatCatalogService(), authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<String> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<String> response =
                tool.prepareReserveLibrarySeat(null, "3179");

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.provider()).isEqualTo("LIBRARY");
        verifyNoInteractions(sessionStore);
        verifyNoInteractions(actionService);
    }

    @Test
    void prepareMessageShowsVisibleSeatLabelNotExternalSeatId() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());

        // externalSeatId 3196 is visible seat 91 in 마루열람실(6F)
        McpPrivateToolResponse<String> response =
                tool.prepareReserveLibrarySeat(SESSION_ID, "3196");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("마루열람실(6F) 91번 좌석")
                .contains("confirm_action")
                .doesNotContain("3196번");
    }

    @Test
    void prepareWarnsWhenSeatIsGraduateOnly() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());

        // externalSeatId 3044 is in 대학원열람실(6F), audience graduate_only
        McpPrivateToolResponse<String> response =
                tool.prepareReserveLibrarySeat(SESSION_ID, "3044");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("대학원 전용");
    }
}
