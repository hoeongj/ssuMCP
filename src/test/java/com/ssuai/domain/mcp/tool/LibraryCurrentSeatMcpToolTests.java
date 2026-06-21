package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationResult;

class LibraryCurrentSeatMcpToolTests {

    private static final Instant EXPIRES = Instant.parse("2026-06-05T10:05:00Z");
    private static final String SESSION_ID = "session-abc";
    private static final String SESSION_KEY = "key-xyz";
    private static final String TOKEN = "pyxis-tok";

    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private McpAuthHelper authHelper;
    private LibraryCurrentSeatMcpTool tool;

    @BeforeEach
    void setUp() {
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibraryCurrentSeatMcpTool(sessionStore, reservationConnector, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<String> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<String> response = tool.getMyLibrarySeat(null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(sessionStore);
    }

    @Test
    void returnsAuthRequiredWhenTokenMissing() {
        McpPrivateToolResponse<String> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.empty());
        when(authHelper.<String>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<String> response = tool.getMyLibrarySeat(SESSION_ID);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void returnsNoReservationMessageWhenNoCurrentCharge() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());

        McpPrivateToolResponse<String> response = tool.getMyLibrarySeat(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isEqualTo("현재 예약된 좌석이 없습니다.");
    }

    @Test
    void returnsFormattedCurrentReservationInfo() {
        LibraryReservationResult current =
                new LibraryReservationResult(1966801L, "열람실", "74", "09:00", "18:00");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.of(current));

        McpPrivateToolResponse<String> response = tool.getMyLibrarySeat(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("열람실")
                .contains("74번")
                .contains("09:00")
                .contains("18:00")
                .contains("1966801");
    }
}
