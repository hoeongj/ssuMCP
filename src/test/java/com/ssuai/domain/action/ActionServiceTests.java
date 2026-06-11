package com.ssuai.domain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.ssuai.domain.library.reservation.LibraryReservationRequest;

class ActionServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-05T10:00:00Z");
    private static final String STUDENT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ACTION_TYPE = "LIBRARY_SEAT_RESERVATION";

    private ActionAuditRepository repository;
    private SimpleMeterRegistry meterRegistry;
    private ActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActionAuditRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new ActionService(
                repository,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry);
        when(repository.save(any(ActionAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void claimMovesPendingToExecutingThenCompletesSuccess() {
        ActionAudit created = service.createPendingAction(
                STUDENT_ID, ACTION_TYPE, new LibraryReservationRequest(101L));
        assertCounter("prepared");

        when(repository.lockByStudentIdAndStatus(eq(STUDENT_ID), eq(ActionStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(created));

        ActionAudit claimed = service.claimPendingAction(STUDENT_ID);
        assertThat(claimed.getStatus()).isEqualTo(ActionStatus.EXECUTING);
        assertThat(claimed.getConfirmedAt()).isEqualTo(NOW);
        assertCounter("executing");

        ActionAudit done = service.completeAction(claimed, ActionService.OUTCOME_SUCCESS, "ok");
        assertThat(done.getStatus()).isEqualTo(ActionStatus.SUCCESS);
        assertThat(done.getOutcomeCode()).isEqualTo("SUCCESS");
        assertThat(done.getCompletedAt()).isEqualTo(NOW);
        assertThat(done.getPayload()).contains("\"seatId\":101");
        assertTerminalCounter("success", ActionService.OUTCOME_SUCCESS);
    }

    @Test
    void completeWithFailureOutcomeMarksActionFailedNotSuccess() {
        ActionAudit executing = ActionAudit.pending(STUDENT_ID, ACTION_TYPE, "{\"seatId\":101}", NOW);
        executing.markExecuting(NOW);

        ActionAudit done = service.completeAction(
                executing, ActionService.OUTCOME_FAILURE_RACE, "좌석 선점됨");

        assertThat(done.getStatus()).isEqualTo(ActionStatus.FAILED);
        assertThat(done.getOutcomeCode()).isEqualTo("FAILURE_RACE");
        assertThat(done.getCompletedAt()).isEqualTo(NOW);
        assertTerminalCounter("failed", ActionService.OUTCOME_FAILURE_RACE);
    }

    @Test
    void claimThrowsAndExpiresActionAfterTtl() {
        ActionAudit pending = ActionAudit.pending(
                STUDENT_ID,
                ACTION_TYPE,
                "{\"seatId\":101}",
                NOW.minus(ActionService.ACTION_TTL).minusSeconds(1));
        when(repository.lockByStudentIdAndStatus(eq(STUDENT_ID), eq(ActionStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.claimPendingAction(STUDENT_ID))
                .isInstanceOf(ActionService.ActionExpiredException.class);

        assertThat(pending.getStatus()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(pending.getExpiredAt()).isEqualTo(NOW);
    }

    @Test
    void claimThrowsWhenNoPendingAction() {
        when(repository.lockByStudentIdAndStatus(eq(STUDENT_ID), eq(ActionStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.claimPendingAction(STUDENT_ID))
                .isInstanceOf(ActionService.NoPendingActionException.class);
    }

    @Test
    void expireStaleActionsMarksOldPendingActionsExpired() {
        ActionAudit stale = ActionAudit.pending(
                STUDENT_ID,
                ACTION_TYPE,
                "{\"floor\":\"5F\",\"seatId\":\"205\"}",
                NOW.minus(ActionService.ACTION_TTL).minusSeconds(1));
        when(repository.findAllByStatusAndCreatedAtBefore(
                ActionStatus.PENDING, NOW.minus(ActionService.ACTION_TTL)))
                .thenReturn(List.of(stale));

        service.expireStaleActions();

        assertThat(stale.getStatus()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(stale.getExpiredAt()).isEqualTo(NOW);
        verify(repository).saveAll(List.of(stale));
    }

    private void assertCounter(String status) {
        assertThat(meterRegistry.get("library.action")
                .tag("action_type", ACTION_TYPE)
                .tag("status", status)
                .counter()
                .count()).isEqualTo(1.0);
    }

    private void assertTerminalCounter(String status, String outcome) {
        assertThat(meterRegistry.get("library.action")
                .tag("action_type", ACTION_TYPE)
                .tag("status", status)
                .tag("outcome", outcome)
                .counter()
                .count()).isEqualTo(1.0);
    }
}
