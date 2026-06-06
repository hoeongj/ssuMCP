package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;

class LibrarySwapMcpToolTests {

    private static final Instant EXPIRES = Instant.parse("2026-06-05T10:05:00Z");
    private static final String SESSION_ID = "session-abc";
    private static final String SESSION_KEY = "key-xyz";
    private static final String TOKEN = "pyxis-tok";

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private McpAuthHelper authHelper;
    private LibrarySwapMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibrarySwapMcpTool(actionService, sessionStore, reservationConnector, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<String> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<String> response = tool.prepareSwapLibrarySeat(null, "3200");

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(sessionStore);
        verifyNoInteractions(actionService);
    }

    @Test
    void returnsAuthRequiredWhenTokenMissing() {
        McpPrivateToolResponse<String> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<String> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3200");

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(actionService);
    }

    @Test
    void suggestsReserveWhenNoCurrentCharge() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());

        McpPrivateToolResponse<String> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3200");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("prepare_reserve_library_seat");
        verifyNoInteractions(actionService);
    }

    @Test
    void createsPendingSwapActionWithOldChargeAndNewSeat() {
        LibraryReservationResult current =
                new LibraryReservationResult(1966801L, "열람실", "74", "09:00", "18:00");
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.of(current));

        McpPrivateToolResponse<String> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3200");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("1966801")
                .contains("3200")
                .contains("confirm_action");
        verify(actionService).createPendingAction(
                SESSION_KEY,
                LibrarySwapMcpTool.ACTION_TYPE,
                new LibrarySwapRequest(1966801L, 3200L));
    }

    @Test
    void throwsWhenSeatIdIsBlank() {
        assertThatThrownBy(() -> tool.prepareSwapLibrarySeat(SESSION_ID, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new_seat_id is required");
    }

    @Test
    void throwsWhenSeatIdIsNotNumeric() {
        assertThatThrownBy(() -> tool.prepareSwapLibrarySeat(SESSION_ID, "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }
}
