package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.reservation.LibraryCancelRequest;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

class ConfirmActionMcpToolTests {

    private static final String SESSION_ID = "mcp-session";
    private static final String SESSION_KEY = "library-key";
    private static final String TOKEN = "pyxis-token";
    private static final long ACTION_ID = 77L;

    private ActionService actionService;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector reservationConnector;
    private LibraryReservationIntentTransactions intentTransactions;
    private LibrarySeatEventPublisher seatEventPublisher;
    private McpAuthHelper authHelper;
    private ConfirmActionMcpTool tool;

    @BeforeEach
    void setUp() {
        actionService = mock(ActionService.class);
        sessionStore = mock(LibrarySessionStore.class);
        reservationConnector = mock(LibraryReservationConnector.class);
        intentTransactions = mock(LibraryReservationIntentTransactions.class);
        seatEventPublisher = mock(LibrarySeatEventPublisher.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new ConfirmActionMcpTool(
                actionService,
                sessionStore,
                reservationConnector,
                intentTransactions,
                seatEventPublisher,
                authHelper);

        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(SESSION_KEY, SESSION_ID)));
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

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

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

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("이미 선점").contains("intentId=12");
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_FAILURE_RACE), any());
        verify(reservationConnector, never()).reserve(eq(TOKEN), any(LibraryReservationRequest.class));
    }

    @Test
    void cancelNotAvailableStateReturnsUserFriendlyMessage() {
        ActionAudit action = claimableAction(LibraryCancelMcpTool.ACTION_TYPE);
        when(actionService.payload(action, LibraryCancelRequest.class))
                .thenReturn(new LibraryCancelRequest(1966693L));
        doThrow(new LibrarySeatNotAvailableException("warning.smuf.notAvailableState"))
                .when(reservationConnector).discharge(TOKEN, 1966693L);

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("아직 입실 전")
                .contains("반납할 수 없어요")
                .contains("자동 취소");
        verify(actionService).completeAction(action, ActionService.OUTCOME_FAILURE_UPSTREAM, "미입실 상태로 반납 불가");
    }

    @Test
    void cancelSuccessPublishesSeatEventAfterDischarge() {
        ActionAudit action = claimableAction(LibraryCancelMcpTool.ACTION_TYPE);
        when(actionService.payload(action, LibraryCancelRequest.class))
                .thenReturn(new LibraryCancelRequest(1966693L, 54, 926L));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        verify(reservationConnector).discharge(TOKEN, 1966693L);
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_SUCCESS), any());
        verify(seatEventPublisher).cancel(54, 926L);
    }

    @Test
    void swapNotAvailableStateKeepsCurrentReservationAndDoesNotReserveNewSeat() {
        ActionAudit action = claimableAction(LibrarySwapMcpTool.ACTION_TYPE);
        when(actionService.payload(action, LibrarySwapRequest.class))
                .thenReturn(new LibrarySwapRequest(1966693L, 3179L));
        doThrow(new LibrarySeatNotAvailableException("warning.smuf.notAvailableState"))
                .when(reservationConnector).discharge(TOKEN, 1966693L);

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data())
                .contains("아직 입실 전")
                .contains("자리 변경을 할 수 없어요")
                .contains("기존 예약은 그대로 유지");
        verify(reservationConnector, never()).reserve(eq(TOKEN), any(LibraryReservationRequest.class));
        verify(actionService).completeAction(action, ActionService.OUTCOME_FAILURE_UPSTREAM, "미입실 상태로 이석 불가");
    }

    @Test
    void swapSuccessPublishesDischargeAndReserveEvents() {
        ActionAudit action = claimableAction(LibrarySwapMcpTool.ACTION_TYPE);
        when(actionService.payload(action, LibrarySwapRequest.class))
                .thenReturn(new LibrarySwapRequest(1966693L, 3179L, 54, 926L));
        when(reservationConnector.reserve(TOKEN, new LibraryReservationRequest(3179L)))
                .thenReturn(new LibraryReservationResult(
                        200L,
                        "room",
                        "74",
                        "09:00",
                        "13:00",
                        58,
                        3179L));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        verify(seatEventPublisher).swapDischarge(54, 926L);
        verify(seatEventPublisher).swapReserve(58, 3179L);
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_SUCCESS), any());
    }

    @Test
    void explicitActionIdConfirmsThatExactAction() {
        // Caller passes a specific action_id; it is owned + PENDING, so it is claimed by id
        // (not by "latest") and executed.
        ActionAudit action = ActionAudit.pending(SESSION_KEY, LibraryCancelMcpTool.ACTION_TYPE, "{}", Instant.now());
        ReflectionTestUtils.setField(action, "id", ACTION_ID);
        when(actionService.claimPendingActionById(SESSION_KEY, ACTION_ID)).thenReturn(action);
        when(actionService.payload(action, LibraryCancelRequest.class))
                .thenReturn(new LibraryCancelRequest(1966693L, 54, 926L));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, ACTION_ID);

        assertThat(response.status()).isEqualTo("OK");
        verify(reservationConnector).discharge(TOKEN, 1966693L);
        verify(actionService).completeAction(eq(action), eq(ActionService.OUTCOME_SUCCESS), any());
        // No-id "latest" lookup must NOT be consulted when an explicit id is given.
        verify(actionService, never()).findActivePendingActions(any());
    }

    @Test
    void explicitActionIdNotOwnedIsDeniedWithoutExecuting() {
        // The id belongs to another session/owner (or is unknown): the ownership-filtered
        // locked claim finds nothing and raises NoPendingActionException. confirm_action must
        // deny with a clear error and must NEVER execute a different action.
        long foreignActionId = 999L;
        when(actionService.claimPendingActionById(SESSION_KEY, foreignActionId))
                .thenThrow(new ActionService.NoPendingActionException());

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, foreignActionId);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("본인 세션의 액션이 아닙니다");
        // Executor/connector untouched: no cross-owner or fallback execution.
        verify(reservationConnector, never()).discharge(any(), anyLong());
        verify(reservationConnector, never()).reserve(any(), any(LibraryReservationRequest.class));
        verify(intentTransactions, never()).createImmediateReservation(any(), anyLong(), anyLong(), any());
        verify(actionService, never()).claimPendingAction(any());
    }

    @Test
    void explicitActionIdExpiredIsDeniedWithoutExecuting() {
        // Owned + PENDING but past its TTL: the locked claim marks it EXPIRED and throws.
        when(actionService.claimPendingActionById(SESSION_KEY, ACTION_ID))
                .thenThrow(new ActionService.ActionExpiredException());

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, ACTION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("만료");
        verify(reservationConnector, never()).discharge(any(), anyLong());
        verify(reservationConnector, never()).reserve(any(), any(LibraryReservationRequest.class));
    }

    @Test
    void noIdWithZeroPendingReturnsNothingToConfirm() {
        when(actionService.findActivePendingActions(SESSION_KEY)).thenReturn(List.of());

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("대기 중인 액션이 없습니다");
        verify(actionService, never()).claimPendingActionById(any(), any());
        verify(reservationConnector, never()).discharge(any(), anyLong());
    }

    @Test
    void noIdWithMultiplePendingRefusesAndAsksForActionId() {
        // Only reachable via a concurrent-prepare race; confirm must refuse, not guess.
        ActionAudit a = ActionAudit.pending(SESSION_KEY, LibraryCancelMcpTool.ACTION_TYPE, "{}", Instant.now());
        ActionAudit b = ActionAudit.pending(SESSION_KEY, LibraryReservationMcpTool.ACTION_TYPE, "{}", Instant.now());
        when(actionService.findActivePendingActions(SESSION_KEY)).thenReturn(List.of(a, b));

        McpPrivateToolResponse<String> response = tool.confirmAction(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).contains("action_id를 지정");
        verify(actionService, never()).claimPendingActionById(any(), any());
        verify(reservationConnector, never()).discharge(any(), anyLong());
        verify(reservationConnector, never()).reserve(any(), any(LibraryReservationRequest.class));
    }

    private ActionAudit reservationAction() {
        ActionAudit action = claimableAction(LibraryReservationMcpTool.ACTION_TYPE);
        when(actionService.payload(action, LibraryReservationRequest.class))
                .thenReturn(new LibraryReservationRequest(3179L));
        return action;
    }

    private ActionAudit claimableAction(String actionType) {
        ActionAudit action = ActionAudit.pending(SESSION_KEY, actionType, "{}", Instant.now());
        ReflectionTestUtils.setField(action, "id", ACTION_ID);
        // No-id confirm path: exactly one active pending action, claimed by its id.
        when(actionService.findActivePendingActions(SESSION_KEY)).thenReturn(List.of(action));
        when(actionService.claimPendingActionById(SESSION_KEY, ACTION_ID)).thenReturn(action);
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
