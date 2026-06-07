package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

class ConfirmActionMcpToolTests {

    private static final String SESSION_ID = "mcp-session";
    private static final String SESSION_KEY = "library-key";
    private static final String TOKEN = "pyxis-token";

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private McpAuthHelper authHelper;
    private ConfirmActionMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new ConfirmActionMcpTool(actionService, sessionStore, reservationConnector, authHelper);

        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
    }

    /** Stubs a claimable reservation action and returns it. */
    private ActionAudit reservationAction() {
        ActionAudit action = ActionAudit.pending(
                SESSION_KEY, LibraryReservationMcpTool.ACTION_TYPE, "{\"seatId\":3179}", Instant.now());
        when(actionService.findPendingAction(SESSION_KEY)).thenReturn(Optional.of(action));
        when(actionService.isExpired(action)).thenReturn(false);
        when(actionService.claimPendingAction(SESSION_KEY)).thenReturn(action);
        when(actionService.payload(action, LibraryReservationRequest.class))
                .thenReturn(new LibraryReservationRequest(3179L));
        return action;
    }

    @Test
    void reservationSuccessCompletesWithSuccessOutcome() {
        ActionAudit action = reservationAction();
        when(reservationConnector.reserve(eq(TOKEN), any(LibraryReservationRequest.class)))
                .thenReturn(new LibraryReservationResult(1966693L, "마루열람실(6F)", "74", "14:59", "18:59"));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("예약 완료").contains("74").contains("1966693");
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_SUCCESS), any());
    }

    @Test
    void reservationRaceCompletesWithFailureNotSuccess() {
        ActionAudit action = reservationAction();
        when(reservationConnector.reserve(eq(TOKEN), any(LibraryReservationRequest.class)))
                .thenThrow(new LibrarySeatNotAvailableException("error.seat.alreadyCharged"));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID);

        // The audit must NOT be recorded as success when the upstream reservation failed.
        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("선점");
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_FAILURE_RACE), any());
    }
}
