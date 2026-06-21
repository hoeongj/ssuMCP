package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.reservation.LibraryPrepareResult;
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
        tool = new LibrarySwapMcpTool(
                actionService, sessionStore, reservationConnector, new LibrarySeatCatalogService(), authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<LibraryPrepareResult> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<LibraryPrepareResult>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareSwapLibrarySeat(null, "3200");

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

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3200");

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(actionService);
    }

    @Test
    void suggestsReserveWhenNoCurrentCharge() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.empty());

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3200");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().message()).contains("prepare_reserve_library_seat");
        verifyNoInteractions(actionService);
    }

    @Test
    void createsPendingSwapActionWithOldChargeAndNewSeat() {
        // E2E fixture: current seat 마루열람실(6F) 89, target externalSeatId 3196 = visible seat 91
        LibraryReservationResult current =
                new LibraryReservationResult(1968552L, "마루열람실(6F)", "89", "09:00", "18:00");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.of(current));
        ActionAudit audit42 = mockAudit(42L);
        when(actionService.createPendingAction(any(), any(), any())).thenReturn(audit42);

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3196");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().message())
                .contains("1968552")
                .contains("마루열람실(6F) 91번 좌석")
                .contains("confirm_action")
                .doesNotContain("3196번");
        verify(actionService).createPendingAction(
                SESSION_KEY,
                LibrarySwapMcpTool.ACTION_TYPE,
                new LibrarySwapRequest(1968552L, 3196L));
    }

    @Test
    void fallsBackToSeatIdNoticeWhenSeatNotInCatalog() {
        LibraryReservationResult current =
                new LibraryReservationResult(1966801L, "열람실", "74", "09:00", "18:00");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.of(current));
        ActionAudit audit43 = mockAudit(43L);
        when(actionService.createPendingAction(any(), any(), any())).thenReturn(audit43);

        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareSwapLibrarySeat(SESSION_ID, "999999");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().message())
                .contains("좌석ID 999999")
                .contains("좌석 번호 미확인");
    }

    @Test
    void warnsWhenTargetSeatIsGraduateOnly() {
        LibraryReservationResult current =
                new LibraryReservationResult(1966801L, "마루열람실(6F)", "74", "09:00", "18:00");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        when(reservationConnector.getCurrentCharge(TOKEN)).thenReturn(Optional.of(current));
        ActionAudit audit44 = mockAudit(44L);
        when(actionService.createPendingAction(any(), any(), any())).thenReturn(audit44);

        // externalSeatId 3044 is in 대학원열람실(6F), audience graduate_only
        McpPrivateToolResponse<LibraryPrepareResult> response = tool.prepareSwapLibrarySeat(SESSION_ID, "3044");

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().message()).contains("대학원 전용");
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

    private static ActionAudit mockAudit(long id) {
        ActionAudit audit = mock(ActionAudit.class);
        when(audit.getId()).thenReturn(id);
        return audit;
    }
}
