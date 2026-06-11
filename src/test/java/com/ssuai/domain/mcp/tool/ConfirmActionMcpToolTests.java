package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;

class ConfirmActionMcpToolTests {

    private static final String SESSION_ID = "mcp-session";
    private static final String SESSION_KEY = "library-key";
    private static final String TOKEN = "pyxis-token";
    private static final long ACTION_ID = 77L;

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private LibraryReservationIntentTransactions intentTransactions;
    private McpAuthHelper authHelper;
    private ConfirmActionMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        intentTransactions = mock(LibraryReservationIntentTransactions.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new ConfirmActionMcpTool(
                actionService, sessionStore, reservationConnector, intentTransactions, authHelper);

        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(SESSION_KEY));
        when(sessionStore.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
    }

    @Test
    void reservationSuccessCompletesWithSuccessOutcome() {
        ActionAudit action = reservationAction();
        when(intentTransactions.createImmediateReservation(
                eq(SESSION_KEY), eq(ACTION_ID), eq(3179L), eq(ActionService.ACTION_TTL)))
                .thenReturn(intentView(11L, LibraryReservationIntentStatus.REQUESTED, null));
        when(intentTransactions.findById(11L))
                .thenReturn(Optional.of(intentView(
                        11L,
                        LibraryReservationIntentStatus.SUCCEEDED,
                        "room 74 reserved, chargeId=1966693, time=14:59~18:59")));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("예약 intent 큐 처리 완료")
                .contains("intentId=11")
                .contains("74")
                .contains("1966693");
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_SUCCESS), any());
        verify(reservationConnector, never()).reserve(eq(TOKEN), any(LibraryReservationRequest.class));
    }

    @Test
    void reservationRaceCompletesWithFailureNotSuccess() {
        ActionAudit action = reservationAction();
        when(intentTransactions.createImmediateReservation(
                eq(SESSION_KEY), eq(ACTION_ID), eq(3179L), eq(ActionService.ACTION_TTL)))
                .thenReturn(intentView(12L, LibraryReservationIntentStatus.REQUESTED, null));
        when(intentTransactions.findById(12L))
                .thenReturn(Optional.of(intentView(
                        12L,
                        LibraryReservationIntentStatus.FAILED_RACE,
                        "Seat was already taken upstream.")));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("이미 선점").contains("intentId=12");
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_FAILURE_RACE), any());
        verify(reservationConnector, never()).reserve(eq(TOKEN), any(LibraryReservationRequest.class));
    }

    private ActionAudit reservationAction() {
        ActionAudit action = ActionAudit.pending(
                SESSION_KEY, LibraryReservationMcpTool.ACTION_TYPE, "{\"seatId\":3179}", Instant.now());
        ReflectionTestUtils.setField(action, "id", ACTION_ID);
        when(actionService.findPendingAction(SESSION_KEY)).thenReturn(Optional.of(action));
        when(actionService.isExpired(action)).thenReturn(false);
        when(actionService.claimPendingAction(SESSION_KEY)).thenReturn(action);
        when(actionService.payload(action, LibraryReservationRequest.class))
                .thenReturn(new LibraryReservationRequest(3179L));
        return action;
    }

    private static LibraryReservationIntentView intentView(
            Long intentId,
            LibraryReservationIntentStatus status,
            String outcomeMessage) {
        Instant now = Instant.now();
        boolean terminal = status != LibraryReservationIntentStatus.REQUESTED;
        return new LibraryReservationIntentView(
                intentId,
                status,
                0,
                now,
                now.plusSeconds(300),
                terminal ? now : null,
                terminal ? status.name() : null,
                outcomeMessage,
                3179L,
                ACTION_ID);
    }
}
