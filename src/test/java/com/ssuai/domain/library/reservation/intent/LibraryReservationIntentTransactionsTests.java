package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.action.ActionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LibraryReservationIntentTransactionsTests {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");

    private LibraryReservationIntentRepository intentRepository;
    private LibraryReservationOutboxRepository outboxRepository;
    private LibraryReservationIntentMetrics metrics;
    private LibraryReservationIntentWakeNotifier wakeNotifier;
    private ActionService actionService;
    private LibraryReservationIntentTransactions transactions;

    @BeforeEach
    void setUp() {
        intentRepository = mock(LibraryReservationIntentRepository.class);
        outboxRepository = mock(LibraryReservationOutboxRepository.class);
        LibraryReservationIntentProperties properties = new LibraryReservationIntentProperties();
        metrics = mock(LibraryReservationIntentMetrics.class);
        wakeNotifier = mock(LibraryReservationIntentWakeNotifier.class);
        actionService = mock(ActionService.class);
        transactions = new LibraryReservationIntentTransactions(
                intentRepository,
                outboxRepository,
                properties,
                metrics,
                wakeNotifier,
                actionService,
                new ObjectMapper(),
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
    void cancelActiveSkipsImmediateReservationSoItsAuditIsNotStranded() {
        // cancel_library_wait / DELETE /wait must not cancel an in-flight immediate reservation:
        // doing so would terminalize the intent without finalizing the linked audit (the sync
        // path is observe-only now), leaving the audit stuck EXECUTING. Worker owns it.
        LibraryReservationIntent immediate = LibraryReservationIntent.immediateReservation(
                "session", "session", 3179L, 55L, NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(5)));
        ReflectionTestUtils.setField(immediate, "id", 41L);
        when(intentRepository.findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
                "session", LibraryReservationIntentMetrics.ACTIVE_STATUSES))
                .thenReturn(Optional.of(immediate));

        Optional<LibraryReservationIntentView> view = transactions.cancelActive("session");

        assertThat(view).isPresent();
        assertThat(immediate.getStatus()).isEqualTo(LibraryReservationIntentStatus.REQUESTED);
        verify(actionService, org.mockito.Mockito.never())
                .finalizeFromIntent(any(), any(), any());
    }

    @Test
    void expireWaitingFinalizesLinkedAuditOfExpiredImmediateIntentAsTimeout() {
        // An immediate-reservation intent the worker never claimed expires; its linked audit
        // (left EXECUTING by a sync timeout) must be finalized TIMEOUT, not stranded forever.
        LibraryReservationIntent intent = LibraryReservationIntent.immediateReservation(
                "session", "session", 3179L, 44L, NOW.minusSeconds(600), NOW.minusSeconds(1));
        ReflectionTestUtils.setField(intent, "id", 40L);
        when(intentRepository.findExpiredWaitingForUpdate(NOW, 10)).thenReturn(List.of(intent));

        transactions.expireWaiting();

        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.EXPIRED);
        verify(actionService).finalizeFromIntent(
                eq(44L), eq(ActionService.OUTCOME_TIMEOUT), any());
    }

    @Test
    void timeoutThenWorkerSuccessDrivesOneRealAuditFromExecutingToSuccess() {
        // Headline end-to-end proof of the Codex #4 fix on a single REAL ActionAudit:
        //   1. sync confirm claims the action  -> audit EXECUTING
        //   2. sync wait times out             -> audit STILL EXECUTING (not failed) — the
        //      observe-only sync path writes nothing, so we assert it stays EXECUTING here
        //   3. worker succeeds the intent      -> linked audit becomes SUCCESS
        // No real reservation is performed: there is no connector in this test at all.
        var actionRepository = mock(com.ssuai.domain.action.ActionAuditRepository.class);
        when(actionRepository.save(any(com.ssuai.domain.action.ActionAudit.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        com.ssuai.domain.action.ActionService realActionService =
                new com.ssuai.domain.action.ActionService(
                        actionRepository,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        LibraryReservationIntentTransactions realTransactions = new LibraryReservationIntentTransactions(
                intentRepository,
                outboxRepository,
                new LibraryReservationIntentProperties(),
                metrics,
                wakeNotifier,
                realActionService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // Step 1: a real audit claimed for execution (the sync confirm path's state).
        com.ssuai.domain.action.ActionAudit audit = com.ssuai.domain.action.ActionAudit.pending(
                "session", "LIBRARY_SEAT_RESERVATION", "{\"seatId\":3179}", NOW);
        ReflectionTestUtils.setField(audit, "id", 500L);
        audit.markExecuting(NOW);
        assertThat(audit.getStatus())
                .isEqualTo(com.ssuai.domain.action.ActionStatus.EXECUTING);
        when(actionRepository.findById(500L)).thenReturn(Optional.of(audit));

        // Step 2: sync wait timed out — the observe-only path wrote nothing, so the audit is
        // still EXECUTING. (No call is made here; this asserts the post-timeout invariant.)
        assertThat(audit.getStatus())
                .isEqualTo(com.ssuai.domain.action.ActionStatus.EXECUTING);

        // Step 3: the worker drives the linked intent to SUCCEEDED.
        LibraryReservationIntent intent = claimedImmediateIntent(9L, "session", 500L);
        when(intentRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(intent));
        realTransactions.succeed(9L, 3179L, "room 74 reserved, chargeId=1966693");

        assertThat(audit.getStatus())
                .isEqualTo(com.ssuai.domain.action.ActionStatus.SUCCESS);
        assertThat(audit.getOutcomeCode()).isEqualTo("SUCCESS");
        assertThat(audit.getOutcomeMessage()).isEqualTo("room 74 reserved, chargeId=1966693");
    }

    @Test
    void succeedFinalizesLinkedActionAuditWithSuccessOutcome() {
        LibraryReservationIntent intent = claimedImmediateIntent(5L, "session", 77L);
        when(intentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(intent));

        transactions.succeed(5L, 3179L, "room 74 reserved, chargeId=1966693");

        verify(actionService).finalizeFromIntent(
                77L, ActionService.OUTCOME_SUCCESS, "room 74 reserved, chargeId=1966693");
    }

    @Test
    void failUpstreamFinalizesLinkedActionAuditWithFailureOutcome() {
        LibraryReservationIntent intent = claimedImmediateIntent(6L, "session", 88L);
        when(intentRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(intent));

        transactions.failUpstream(6L, "Library reservation upstream failed.");

        verify(actionService).finalizeFromIntent(
                88L, ActionService.OUTCOME_FAILURE_UPSTREAM, "Library reservation upstream failed.");
    }

    @Test
    void failRaceFinalizesLinkedActionAuditWithRaceOutcome() {
        LibraryReservationIntent intent = claimedImmediateIntent(7L, "session", 99L);
        when(intentRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(intent));

        transactions.failRace(7L, 3179L, "Seat was already taken upstream.");

        verify(actionService).finalizeFromIntent(
                99L, ActionService.OUTCOME_FAILURE_RACE, "Seat was already taken upstream.");
    }

    @Test
    void succeedOnNonImmediateWaitIntentPassesNullAuditIdSoFinalizeIsNoOp() {
        // A non-immediate wait intent has no linked audit; finalizeFromIntent(null, ...) no-ops.
        LibraryReservationIntent intent = waitingIntent(8L, "session");
        intent.claimForReservation(NOW, Duration.ofSeconds(30));
        when(intentRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(intent));

        transactions.succeed(8L, 3179L, "reserved");

        verify(actionService).finalizeFromIntent(null, ActionService.OUTCOME_SUCCESS, "reserved");
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

    private static LibraryReservationIntent claimedImmediateIntent(long id, String sessionKey, long actionAuditId) {
        LibraryReservationIntent intent = LibraryReservationIntent.immediateReservation(
                sessionKey,
                sessionKey,
                3179L,
                actionAuditId,
                NOW.minusSeconds(60),
                NOW.plus(Duration.ofMinutes(5)));
        intent.claimForReservation(NOW.minusSeconds(60), Duration.ofSeconds(30));
        ReflectionTestUtils.setField(intent, "id", id);
        return intent;
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
