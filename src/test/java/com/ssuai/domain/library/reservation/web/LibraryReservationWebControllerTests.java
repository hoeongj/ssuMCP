package com.ssuai.domain.library.reservation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationService;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;
import com.ssuai.domain.library.reservation.intent.LibraryIntentSseRegistry;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.domain.library.reservation.intent.LibraryReservationRegistrationResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ActiveProfiles("test")
@WebMvcTest(LibraryReservationWebController.class)
class LibraryReservationWebControllerTests {

    private static final String SESSION_KEY = "session-key";
    private static final String TOKEN = "pyxis-token";
    private static final long ACTION_ID = 77L;

    private final MockMvc mockMvc;

    @MockitoBean
    private ActionService actionService;

    @MockitoBean
    private LibrarySessionStore librarySessionStore;

    @MockitoBean
    private LibraryReservationConnector reservationConnector;

    @MockitoBean
    private LibraryReservationIntentTransactions intentTransactions;

    @MockitoBean
    private LibrarySeatEventPublisher seatEventPublisher;

    @MockitoBean
    private LibrarySeatRecommendationService recommendationService;

    @MockitoBean
    private LibraryIntentSseRegistry intentSseRegistry;

    @Autowired
    private LibraryReservationWebController controller;

    @Autowired
    LibraryReservationWebControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void setUp() {
        when(librarySessionStore.has(anyString())).thenReturn(true);
        when(librarySessionStore.token(anyString())).thenReturn(Optional.of(TOKEN));
    }

    @Test
    void prepare_withoutLibrarySession_returnsLibraryAuthRequired() throws Exception {
        when(librarySessionStore.has(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/library/reservations/prepare")
                        .contentType("application/json")
                        .content("""
                                {"type":"RESERVE","seatId":3179}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LIBRARY_SESSION_REQUIRED"));
    }

    @Test
    void prepare_withValidSession_returnsPendingAction() throws Exception {
        ActionAudit saved = pendingAction("LIBRARY_SEAT_RESERVATION");
        when(actionService.createPendingAction(anyString(), eq("LIBRARY_SEAT_RESERVATION"), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/api/library/reservations/prepare")
                        .contentType("application/json")
                        .content("""
                                {"type":"RESERVE","seatId":3179}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionId").value(ACTION_ID))
                .andExpect(jsonPath("$.data.actionType").value("LIBRARY_SEAT_RESERVATION"))
                .andExpect(jsonPath("$.data.summary").value("Reserve seat 3179 prepared."));
    }

    @Test
    void confirmReserve_withPendingAction_returnsIntent() throws Exception {
        ActionAudit pending = pendingAction("LIBRARY_SEAT_RESERVATION");
        ActionAudit claimed = pendingAction("LIBRARY_SEAT_RESERVATION");
        LibraryReservationIntentView requested = intentView(
                11L,
                LibraryReservationIntentStatus.REQUESTED,
                null);
        LibraryReservationIntentView succeeded = intentView(
                11L,
                LibraryReservationIntentStatus.SUCCEEDED,
                "Reservation succeeded.");

        when(actionService.findPendingAction(anyString())).thenReturn(Optional.of(pending));
        when(actionService.isExpired(pending)).thenReturn(false);
        when(actionService.claimPendingAction(anyString())).thenReturn(claimed);
        when(actionService.payload(claimed, LibraryReservationRequest.class))
                .thenReturn(new LibraryReservationRequest(3179L));
        when(intentTransactions.createImmediateReservation(anyString(), eq(ACTION_ID), eq(3179L), eq(ActionService.ACTION_TTL)))
                .thenReturn(requested);
        when(intentTransactions.findById(11L)).thenReturn(Optional.of(succeeded));

        mockMvc.perform(post("/api/library/reservations/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.intentId").value(11L))
                .andExpect(jsonPath("$.data.message").value("Reservation succeeded."));
    }

    @Test
    void confirmReserve_syncTimeout_returnsProcessingWithoutFailingAudit() throws Exception {
        // Worker never resolves the intent within the (shrunk) sync window. The controller must
        // NOT terminally fail the audit; it returns a non-terminal PROCESSING status so the
        // still-running worker can finalize the linked audit.
        ReflectionTestUtils.setField(controller, "reservationIntentWait", Duration.ofMillis(20));
        ReflectionTestUtils.setField(controller, "reservationIntentPoll", Duration.ofMillis(5));
        ActionAudit pending = pendingAction("LIBRARY_SEAT_RESERVATION");
        ActionAudit claimed = pendingAction("LIBRARY_SEAT_RESERVATION");
        LibraryReservationIntentView reserving = intentView(
                11L, LibraryReservationIntentStatus.RESERVING, null);

        when(actionService.findPendingAction(anyString())).thenReturn(Optional.of(pending));
        when(actionService.isExpired(pending)).thenReturn(false);
        when(actionService.claimPendingAction(anyString())).thenReturn(claimed);
        when(actionService.payload(claimed, LibraryReservationRequest.class))
                .thenReturn(new LibraryReservationRequest(3179L));
        when(intentTransactions.createImmediateReservation(
                anyString(), eq(ACTION_ID), eq(3179L), eq(ActionService.ACTION_TTL)))
                .thenReturn(intentView(11L, LibraryReservationIntentStatus.REQUESTED, null));
        when(intentTransactions.findById(11L)).thenReturn(Optional.of(reserving));

        mockMvc.perform(post("/api/library/reservations/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.intentId").value(11L));

        verify(actionService, never()).completeAction(any(), any(), any());
        verify(reservationConnector, never()).reserve(any(), any());
    }

    @Test
    void confirmSwap_newSeatFails_compensatesByReReservingOriginalSeat() throws Exception {
        // discharge(old) succeeds, reserve(new) fails → controller must re-reserve the ORIGINAL
        // seat (compensation) so the user is not left holding no seat. Previously the web path
        // returned failure without restoring the old seat.
        ActionAudit pending = pendingAction("LIBRARY_SEAT_SWAP");
        ActionAudit claimed = pendingAction("LIBRARY_SEAT_SWAP");
        LibrarySwapRequest swap = new LibrarySwapRequest(999L, 3179L, 57, 3000L);

        when(actionService.findPendingAction(anyString())).thenReturn(Optional.of(pending));
        when(actionService.isExpired(pending)).thenReturn(false);
        when(actionService.claimPendingAction(anyString())).thenReturn(claimed);
        when(actionService.payload(claimed, LibrarySwapRequest.class)).thenReturn(swap);
        when(reservationConnector.reserve(eq(TOKEN), eq(new LibraryReservationRequest(3179L))))
                .thenThrow(new RuntimeException("new seat reserve failed"));
        when(reservationConnector.reserve(eq(TOKEN), eq(new LibraryReservationRequest(3000L))))
                .thenReturn(new LibraryReservationResult(111L, "6F", "A1", "10:00", "18:00", 57, 3000L));

        mockMvc.perform(post("/api/library/reservations/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED_RACE"));

        // Compensation actually re-reserved the original seat, and the audit reflects the race.
        verify(reservationConnector).reserve(eq(TOKEN), eq(new LibraryReservationRequest(3000L)));
        verify(actionService).completeAction(eq(claimed), eq(ActionService.OUTCOME_FAILURE_RACE), anyString());
    }

    @Test
    void confirmSwap_compensationAlsoFails_returnsPartialFailure() throws Exception {
        // discharge(old) succeeds, reserve(new) fails, AND re-reserving the original seat fails →
        // the user holds NO seat; the audit must record PARTIAL_FAILURE (distinct outcome).
        ActionAudit pending = pendingAction("LIBRARY_SEAT_SWAP");
        ActionAudit claimed = pendingAction("LIBRARY_SEAT_SWAP");
        LibrarySwapRequest swap = new LibrarySwapRequest(999L, 3179L, 57, 3000L);

        when(actionService.findPendingAction(anyString())).thenReturn(Optional.of(pending));
        when(actionService.isExpired(pending)).thenReturn(false);
        when(actionService.claimPendingAction(anyString())).thenReturn(claimed);
        when(actionService.payload(claimed, LibrarySwapRequest.class)).thenReturn(swap);
        when(reservationConnector.reserve(eq(TOKEN), eq(new LibraryReservationRequest(3179L))))
                .thenThrow(new RuntimeException("new seat reserve failed"));
        when(reservationConnector.reserve(eq(TOKEN), eq(new LibraryReservationRequest(3000L))))
                .thenThrow(new RuntimeException("original seat restore failed"));

        mockMvc.perform(post("/api/library/reservations/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED_UPSTREAM"));

        verify(actionService).completeAction(eq(claimed), eq(ActionService.OUTCOME_PARTIAL_FAILURE), anyString());
    }

    @Test
    void registerWait_withNewIntent_returns200() throws Exception {
        LibraryReservationIntentView created = intentView(
                22L,
                LibraryReservationIntentStatus.WAITING_FOR_SEAT,
                null);
        when(intentTransactions.registerWait(anyString(), any()))
                .thenReturn(new LibraryReservationRegistrationResult(created, true));

        mockMvc.perform(post("/api/library/reservations/wait")
                        .contentType("application/json")
                        .content("""
                                {"preferredFloor":"6F","preferredRoomIds":"57,58","seatAttributes":"window","targetSeatId":3179}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intentId").value(22L))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_SEAT"));
    }

    @Test
    void registerWait_withActiveIntent_returnsConflict() throws Exception {
        LibraryReservationIntentView existing = intentView(
                21L,
                LibraryReservationIntentStatus.WAITING_FOR_SEAT,
                "Waiting for seat");
        when(intentTransactions.registerWait(anyString(), any()))
                .thenReturn(new LibraryReservationRegistrationResult(existing, false));

        mockMvc.perform(post("/api/library/reservations/wait")
                        .contentType("application/json")
                        .content("""
                                {"preferredFloor":"6F","preferredRoomIds":"57,58","seatAttributes":"window","targetSeatId":3179}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_WAIT_EXISTS"));
    }

    @Test
    void intentEvents_ownerSubscribingToOwnIntent_opensStream() throws Exception {
        when(intentTransactions.isOwnedBySession(eq(11L), anyString())).thenReturn(true);
        when(intentSseRegistry.createEmitter(11L)).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/library/reservations/wait/events/11"))
                .andExpect(request().asyncStarted());

        verify(intentSseRegistry).createEmitter(11L);
    }

    @Test
    void intentEvents_intentNotOwnedByCaller_returns404AndNoStream() throws Exception {
        // IDOR guard: a valid library session but an intentId owned by someone else
        // (or unknown) must be rejected as 404 and MUST NOT open the stream.
        when(intentTransactions.isOwnedBySession(eq(11L), anyString())).thenReturn(false);

        mockMvc.perform(get("/api/library/reservations/wait/events/11"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        verify(intentSseRegistry, never()).createEmitter(any());
    }

    @Test
    void cancelWait_withNoActiveIntent_returns404() throws Exception {
        when(intentTransactions.cancelActive(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/library/reservations/wait"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private static ActionAudit pendingAction(String actionType) {
        ActionAudit action = ActionAudit.pending(SESSION_KEY, actionType, "{}", Instant.parse("2026-06-12T00:00:00Z"));
        ReflectionTestUtils.setField(action, "id", ACTION_ID);
        return action;
    }

    private static LibraryReservationIntentView intentView(
            Long intentId,
            LibraryReservationIntentStatus status,
            String outcomeMessage) {
        Instant now = Instant.parse("2026-06-12T00:00:00Z");
        boolean terminal = status != LibraryReservationIntentStatus.REQUESTED
                && status != LibraryReservationIntentStatus.WAITING_FOR_SEAT
                && status != LibraryReservationIntentStatus.RESERVING;
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
