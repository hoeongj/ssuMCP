package com.ssuai.domain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.reservation.LibraryReservationRequest;

class ActionServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-05T10:00:00Z");
    private static final String STUDENT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ACTION_TYPE = "LIBRARY_SEAT_RESERVATION";

    private ActionAuditRepository repository;
    private ActionService service;

    @BeforeEach
    void setUp() {
        repository = mock(ActionAuditRepository.class);
        service = new ActionService(
                repository,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any(ActionAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsPendingActionAndConfirmsIt() {
        ActionAudit created = service.createPendingAction(
                STUDENT_ID, ACTION_TYPE, new LibraryReservationRequest("2F", "101"));
        when(repository.findTopByStudentIdAndStatusOrderByCreatedAtDesc(STUDENT_ID, ActionStatus.PENDING))
                .thenReturn(Optional.of(created));

        ActionAudit confirmed = service.confirmAction(STUDENT_ID);

        assertThat(confirmed.getStatus()).isEqualTo(ActionStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.getPayload()).contains("\"floor\":\"2F\"");
    }

    @Test
    void confirmFailsAndExpiresActionAfterTtl() {
        ActionAudit pending = ActionAudit.pending(
                STUDENT_ID,
                ACTION_TYPE,
                "{\"floor\":\"2F\",\"seatId\":\"101\"}",
                NOW.minus(ActionService.ACTION_TTL).minusSeconds(1));
        when(repository.findTopByStudentIdAndStatusOrderByCreatedAtDesc(STUDENT_ID, ActionStatus.PENDING))
                .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.confirmAction(STUDENT_ID))
                .isInstanceOf(ActionService.ActionExpiredException.class);

        assertThat(pending.getStatus()).isEqualTo(ActionStatus.EXPIRED);
        assertThat(pending.getExpiredAt()).isEqualTo(NOW);
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
}
