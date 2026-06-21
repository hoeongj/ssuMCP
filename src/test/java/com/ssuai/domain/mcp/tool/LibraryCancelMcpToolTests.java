package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryCancelRequest;
import com.ssuai.domain.library.reservation.LibraryPrepareResult;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationResult;

class LibraryCancelMcpToolTests {

    private static final Instant EXPIRES = Instant.parse("2026-06-05T10:05:00Z");
    private static final String SESSION_ID = "session-abc";
    private static final String SESSION_KEY = "key-xyz";
    private static final String TOKEN = "pyxis-tok";

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private McpAuthHelper authHelper;
    private LibraryCancelMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibraryCancelMcpTool(actionService, sessionStore, reservationConnector, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<LibraryPrepareResult> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<LibraryPrepareResult>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareCancelLibrarySeat(null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(sessionStore);
        verifyNoInteractions(actionService);
    }

    @Test
    void returnsAuthRequiredWhenTokenMissing() {
        McpPrivateToolResponse<LibraryPrepareResult> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.empty());
        when(authHelper.<LibraryPrepareResult>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareCancelLibrarySeat(SESSION_ID);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(actionService);
    }

    @Test
    void returnsNoReservationMessageWhenNoCurrentCharge() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareCancelLibrarySeat(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().message()).isEqualTo("현재 예약된 좌석이 없습니다.");
        verifyNoInteractions(actionService);
    }

    @Test
    void createsPendingCancelActionWhenActiveChargeExists() {
        LibraryReservationResult current =
                new LibraryReservationResult(1966801L, "열람실", "74", "09:00", "18:00");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.of(current));
        ActionAudit audit = mockAudit(10L);
        when(actionService.createPendingAction(any(), any(), any())).thenReturn(audit);

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareCancelLibrarySeat(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().message())
                .contains("열람실")
                .contains("74번")
                .contains("1966801")
                .contains("confirm_action");
        verify(actionService).createPendingAction(
                SESSION_KEY,
                LibraryCancelMcpTool.ACTION_TYPE,
                new LibraryCancelRequest(1966801L));
    }

    private static ActionAudit mockAudit(long id) {
        ActionAudit audit = mock(ActionAudit.class);
        when(audit.getId()).thenReturn(id);
        return audit;
    }
}
