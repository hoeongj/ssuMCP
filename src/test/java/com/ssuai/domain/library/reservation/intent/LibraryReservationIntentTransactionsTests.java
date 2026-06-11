package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LibraryReservationIntentTransactionsTests {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");

    private LibraryReservationIntentRepository intentRepository;
    private LibraryReservationOutboxRepository outboxRepository;
    private LibraryReservationIntentMetrics metrics;
    private LibraryReservationIntentWakeNotifier wakeNotifier;
    private LibraryReservationIntentTransactions transactions;

    @BeforeEach
    void setUp() {
        intentRepository = mock(LibraryReservationIntentRepository.class);
        outboxRepository = mock(LibraryReservationOutboxRepository.class);
        LibraryReservationIntentProperties properties = new LibraryReservationIntentProperties();
        metrics = mock(LibraryReservationIntentMetrics.class);
        wakeNotifier = mock(LibraryReservationIntentWakeNotifier.class);
        transactions = new LibraryReservationIntentTransactions(
                intentRepository,
                outboxRepository,
                properties,
                metrics,
                wakeNotifier,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registerReturnsExistingActiveIntentWithoutCreatingDuplicate() {
        LibraryReservationIntent existing = waitingIntent(1L, "session");
        when(intentRepository.findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
                "session", LibraryReservationIntentMetrics.ACTIVE_STATUSES))
                .thenReturn(Optional.of(existing));

        LibraryReservationRegistrationResult result =
                transactions.registerWait("session", new LibraryReservationWaitRequest(null, null, null, null, null));

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.intent().intentId()).isEqualTo(1L);
    }

    @Test
    void registerNewWaitNotifiesWorkerWakeAfterSave() {
        when(intentRepository.findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
                "session", LibraryReservationIntentMetrics.ACTIVE_STATUSES))
                .thenReturn(Optional.empty());
        when(intentRepository.save(any(LibraryReservationIntent.class))).thenAnswer(invocation -> {
            LibraryReservationIntent intent = invocation.getArgument(0);
            ReflectionTestUtils.setField(intent, "id", 10L);
            return intent;
        });

        LibraryReservationRegistrationResult result =
                transactions.registerWait("session", new LibraryReservationWaitRequest(null, null, null, null, null));

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.intent().intentId()).isEqualTo(10L);
        verify(wakeNotifier).notifyIntentReady(10L);
    }

    @Test
    void expireWaitingMarksExpiredAndAppendsOutbox() {
        LibraryReservationIntent intent = waitingIntent(3L, "session");
        when(intentRepository.findExpiredWaitingForUpdate(NOW, 10)).thenReturn(List.of(intent));

        int expired = transactions.expireWaiting();

        assertThat(expired).isEqualTo(1);
        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.EXPIRED);
        verify(outboxRepository).save(any(LibraryReservationOutbox.class));
        verify(metrics).countTransition(LibraryReservationIntentStatus.EXPIRED, "EXPIRED");
    }

    @Test
    void createImmediateReservationLinksActionAuditAndSkipsWaitingState() {
        when(intentRepository.save(any(LibraryReservationIntent.class))).thenAnswer(invocation -> {
            LibraryReservationIntent intent = invocation.getArgument(0);
            ReflectionTestUtils.setField(intent, "id", 9L);
            return intent;
        });

        LibraryReservationIntentView view =
                transactions.createImmediateReservation("session", 77L, 3179L, Duration.ofMinutes(5));

        assertThat(view.intentId()).isEqualTo(9L);
        assertThat(view.status()).isEqualTo(LibraryReservationIntentStatus.REQUESTED);
        assertThat(view.targetSeatId()).isEqualTo(3179L);
        assertThat(view.actionAuditId()).isEqualTo(77L);
        verify(outboxRepository).save(any(LibraryReservationOutbox.class));
        verify(metrics).countTransition(LibraryReservationIntentStatus.REQUESTED, null);
        verify(wakeNotifier).notifyIntentReady(9L);
    }

    @Test
    void returnToWaitingAppliesExponentialBackoff() {
        LibraryReservationIntent intent = waitingIntent(4L, "session");
        intent.claimForReservation(NOW, Duration.ofSeconds(30));
        when(intentRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(intent));

        LibraryReservationIntentView view = transactions.returnToWaiting(4L);

        assertThat(view.status()).isEqualTo(LibraryReservationIntentStatus.WAITING_FOR_SEAT);
        assertThat(view.attemptCount()).isEqualTo(1);
        assertThat(view.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    private static LibraryReservationIntent waitingIntent(long id, String sessionKey) {
        LibraryReservationIntent intent = LibraryReservationIntent.requested(
                sessionKey,
                sessionKey,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(60),
                NOW.plus(Duration.ofHours(2)));
        intent.markWaitingForSeat(NOW.minusSeconds(60));
        ReflectionTestUtils.setField(intent, "id", id);
        return intent;
    }
}
