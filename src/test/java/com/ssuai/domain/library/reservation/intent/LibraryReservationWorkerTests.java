package com.ssuai.domain.library.reservation.intent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;

class LibraryReservationWorkerTests {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");
    private static final long SEAT_ID = 3179L;
    private static final String TOKEN = "pyxis-token";

    private LibraryReservationIntentTransactions transactions;
    private LibraryReservationSeatSelector seatSelector;
    private LibrarySessionStore sessionStore;
    private LibraryReservationConnector connector;
    private LibrarySeatEventPublisher seatEventPublisher;
    private LibraryReservationWorker worker;

    @BeforeEach
    void setUp() {
        transactions = mock(LibraryReservationIntentTransactions.class);
        seatSelector = mock(LibraryReservationSeatSelector.class);
        sessionStore = mock(LibrarySessionStore.class);
        connector = mock(LibraryReservationConnector.class);
        seatEventPublisher = mock(LibrarySeatEventPublisher.class);
        worker = new LibraryReservationWorker(transactions, seatSelector, sessionStore, connector, seatEventPublisher);

        when(transactions.claimExpiredLeases()).thenReturn(List.of());
    }

    @Test
    void sameSeatGroupCallsReserveOnceAndFailsOtherIntentsRace() {
        LibraryReservationIntent first = claimedIntent(1L, "session-1");
        LibraryReservationIntent second = claimedIntent(2L, "session-2");
        when(transactions.claimWaitingBatch()).thenReturn(List.of(first, second));
        when(sessionStore.token("session-1")).thenReturn(Optional.of(TOKEN));
        when(sessionStore.token("session-2")).thenReturn(Optional.of(TOKEN));
        when(seatSelector.findAvailableSeat(first)).thenReturn(Optional.of(SEAT_ID));
        when(seatSelector.findAvailableSeat(second)).thenReturn(Optional.of(SEAT_ID));
        when(connector.reserve(eq(TOKEN), any(LibraryReservationRequest.class)))
                .thenReturn(new LibraryReservationResult(100L, "room", "74", "09:00", "13:00"));

        worker.poll();

        verify(connector).reserve(eq(TOKEN), eq(new LibraryReservationRequest(SEAT_ID)));
        verify(transactions).failRace(2L, SEAT_ID,
                "Another local wait intent already attempted this seat in the same worker tick.");
        verify(transactions).succeed(eq(1L), eq(SEAT_ID), any());
        verify(seatEventPublisher).reserve(null, SEAT_ID);
    }

    @Test
    void wakeUsesTheSameWorkerPathAsScheduledPoll() {
        when(transactions.claimWaitingBatch()).thenReturn(List.of());

        worker.wake();

        verify(transactions).expireWaiting();
        verify(transactions).claimExpiredLeases();
        verify(transactions).claimWaitingBatch();
    }

    @Test
    void missingSessionTokenFailsAuthBeforeSeatScan() {
        LibraryReservationIntent intent = claimedIntent(1L, "session-1");
        when(transactions.claimWaitingBatch()).thenReturn(List.of(intent));
        when(sessionStore.token("session-1")).thenReturn(Optional.empty());

        worker.poll();

        verify(transactions).failAuth(1L, "Library session token is missing or expired.");
        verify(seatSelector, never()).findAvailableSeat(intent);
        verify(connector, never()).reserve(any(), any());
    }

    @Test
    void noMatchingSeatReturnsIntentToWaiting() {
        LibraryReservationIntent intent = claimedIntent(1L, "session-1");
        when(transactions.claimWaitingBatch()).thenReturn(List.of(intent));
        when(sessionStore.token("session-1")).thenReturn(Optional.of(TOKEN));
        when(seatSelector.findAvailableSeat(intent)).thenReturn(Optional.empty());

        worker.poll();

        verify(transactions).returnToWaiting(1L);
        verify(connector, never()).reserve(any(), any());
    }

    @Test
    void immediateReservationSkipsSeatScanAndUsesTargetSeat() {
        LibraryReservationIntent intent = claimedImmediateIntent(1L, "session-1");
        when(transactions.claimWaitingBatch()).thenReturn(List.of(intent));
        when(sessionStore.token("session-1")).thenReturn(Optional.of(TOKEN));
        when(connector.reserve(eq(TOKEN), any(LibraryReservationRequest.class)))
                .thenReturn(new LibraryReservationResult(100L, "room", "74", "09:00", "13:00"));

        worker.poll();

        verify(seatSelector, never()).findAvailableSeat(intent);
        verify(connector).reserve(eq(TOKEN), eq(new LibraryReservationRequest(SEAT_ID)));
        verify(transactions).succeed(eq(1L), eq(SEAT_ID), any());
    }

    @Test
    void reserveSuccessPublishesSeatEventWithResultRoomAndSeat() {
        LibraryReservationIntent intent = claimedImmediateIntent(1L, "session-1");
        when(transactions.claimWaitingBatch()).thenReturn(List.of(intent));
        when(sessionStore.token("session-1")).thenReturn(Optional.of(TOKEN));
        when(connector.reserve(eq(TOKEN), any(LibraryReservationRequest.class)))
                .thenReturn(new LibraryReservationResult(100L, "room", "74", "09:00", "13:00", 58, 3179L));

        worker.poll();

        verify(seatEventPublisher).reserve(58, 3179L);
    }

    @Test
    void recentImmediateSeatAttemptFailsLaterClaimedGroupLocally() {
        LibraryReservationIntent first = claimedImmediateIntent(1L, "session-1");
        LibraryReservationIntent second = claimedImmediateIntent(2L, "session-2");
        when(transactions.claimWaitingBatch()).thenReturn(List.of(first, second));
        when(sessionStore.token("session-1")).thenReturn(Optional.of(TOKEN));
        when(sessionStore.token("session-2")).thenReturn(Optional.of(TOKEN));
        when(transactions.hasActiveCompletedImmediateAttemptForSeat(SEAT_ID)).thenReturn(true);

        worker.poll();

        verify(connector, never()).reserve(any(), any());
        verify(transactions).failRace(
                1L, SEAT_ID, "Another recent immediate reservation intent already resolved this seat.");
        verify(transactions).failRace(
                2L, SEAT_ID, "Another recent immediate reservation intent already resolved this seat.");
    }

    @Test
    void reaperVerifiesCurrentChargeForExpiredLease() {
        LibraryReservationIntent intent = claimedIntent(7L, "session-7");
        when(transactions.claimWaitingBatch()).thenReturn(List.of());
        when(transactions.claimExpiredLeases()).thenReturn(List.of(intent));
        when(sessionStore.token("session-7")).thenReturn(Optional.of(TOKEN));
        when(connector.getCurrentCharge(TOKEN))
                .thenReturn(Optional.of(new LibraryReservationResult(101L, "room", "91", "10:00", "14:00")));

        worker.poll();

        verify(connector).getCurrentCharge(TOKEN);
        verify(transactions).succeed(eq(7L), eq(SEAT_ID), any());
    }

    private static LibraryReservationIntent claimedIntent(long id, String sessionKey) {
        LibraryReservationIntent intent = LibraryReservationIntent.requested(
                sessionKey,
                sessionKey,
                null,
                null,
                null,
                SEAT_ID,
                NOW,
                NOW.plus(Duration.ofHours(2)));
        intent.markWaitingForSeat(NOW);
        intent.claimForReservation(NOW, Duration.ofSeconds(30));
        ReflectionTestUtils.setField(intent, "id", id);
        return intent;
    }

    private static LibraryReservationIntent claimedImmediateIntent(long id, String sessionKey) {
        LibraryReservationIntent intent = LibraryReservationIntent.immediateReservation(
                sessionKey,
                sessionKey,
                SEAT_ID,
                77L,
                NOW,
                NOW.plus(Duration.ofMinutes(5)));
        intent.claimForReservation(NOW, Duration.ofSeconds(30));
        ReflectionTestUtils.setField(intent, "id", id);
        return intent;
    }
}
