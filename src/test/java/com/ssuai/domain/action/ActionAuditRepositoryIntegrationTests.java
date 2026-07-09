package com.ssuai.domain.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.domain.library.reservation.LibraryReservationRequest;

/**
 * Real-schema verification of the confirm-action security boundary (ADR 0055): the supersede
 * scoping and the ownership-filtered locked claim are SQL, so they are exercised here against a
 * real H2 (PostgreSQL mode) schema built by Flyway — NOT a mocked repository. No external write
 * is involved; only the in-process action store. These tests fail if a WHERE-clause owner
 * predicate is dropped or the {@code SUPERSEDED} enum value cannot be persisted in
 * {@code action_audit.status VARCHAR(16)}.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
class ActionAuditRepositoryIntegrationTests {

    private static final String OWNER_X = "owner-x-aaaaaaaa-bbbb-cccc";
    private static final String OWNER_Y = "owner-y-dddddddd-eeee-ffff";
    private static final String ACTION_TYPE = "LIBRARY_SEAT_RESERVATION";
    private static final Instant SUPERSEDE_AT = Instant.parse("2026-06-21T05:00:00Z");

    @Autowired
    private ActionAuditRepository repository;

    @Autowired
    private ActionService actionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void prepareSupersedesOnlyTheSameOwnersPendingActions() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        // owner X has a prior PENDING action; owner Y also has one.
        Long priorX = template.execute(status ->
                actionService.createPendingAction(OWNER_X, ACTION_TYPE, new LibraryReservationRequest(101L)).getId());
        Long priorY = template.execute(status ->
                actionService.createPendingAction(OWNER_Y, ACTION_TYPE, new LibraryReservationRequest(201L)).getId());

        // X prepares a NEW action: only X's prior PENDING must be superseded.
        Long newX = template.execute(status ->
                actionService.createPendingAction(OWNER_X, ACTION_TYPE, new LibraryReservationRequest(102L)).getId());

        ActionAudit priorXRow = repository.findById(priorX).orElseThrow();
        ActionAudit newXRow = repository.findById(newX).orElseThrow();
        ActionAudit priorYRow = repository.findById(priorY).orElseThrow();

        assertThat(priorXRow.getStatus()).isEqualTo(ActionStatus.SUPERSEDED);
        assertThat(priorXRow.getExpiredAt()).isNotNull();
        assertThat(newXRow.getStatus()).isEqualTo(ActionStatus.PENDING);
        // The cross-owner guarantee: owner Y's pending action is untouched by owner X's prepare.
        assertThat(priorYRow.getStatus()).isEqualTo(ActionStatus.PENDING);

        // After supersede, owner X has exactly one active PENDING action.
        assertThat(repository.findAllByStudentIdAndStatus(OWNER_X, ActionStatus.PENDING))
                .extracting(ActionAudit::getId)
                .containsExactly(newX);
    }

    @Test
    void actionScopedPrepareSupersedesOnlySameTargetLeavingDifferentTargetPending() {
        // ADR 0086: the MCP library prepare tools call the 4-arg createPendingAction overload,
        // which scopes supersede to (studentId, actionType, targetKey). Re-preparing the SAME
        // seat (same target) supersedes its predecessor; a DIFFERENT seat does not — both stay
        // concurrently PENDING, reproducing G2's "two concurrent reservations" scenario, which
        // confirm_action's action_id disambiguation (ADR 0055) then resolves.
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        Long seat101First = template.execute(status -> actionService
                .createPendingAction(OWNER_X, ACTION_TYPE, "101", new LibraryReservationRequest(101L)).getId());
        Long seat202 = template.execute(status -> actionService
                .createPendingAction(OWNER_X, ACTION_TYPE, "202", new LibraryReservationRequest(202L)).getId());
        Long seat101Second = template.execute(status -> actionService
                .createPendingAction(OWNER_X, ACTION_TYPE, "101", new LibraryReservationRequest(101L)).getId());

        // Re-preparing seat 101 superseded the FIRST seat-101 row (same target)...
        assertThat(repository.findById(seat101First).orElseThrow().getStatus())
                .isEqualTo(ActionStatus.SUPERSEDED);
        // ...but left the seat-202 row (a different, concurrent action) untouched...
        assertThat(repository.findById(seat202).orElseThrow().getStatus())
                .isEqualTo(ActionStatus.PENDING);
        // ...and the new seat-101 row is PENDING.
        assertThat(repository.findById(seat101Second).orElseThrow().getStatus())
                .isEqualTo(ActionStatus.PENDING);

        // Owner X now has exactly two active PENDING actions — confirm_action's no-id path must
        // refuse and ask for an explicit action_id rather than guess between them.
        assertThat(repository.findAllByStudentIdAndStatus(OWNER_X, ActionStatus.PENDING))
                .extracting(ActionAudit::getId)
                .containsExactlyInAnyOrder(seat202, seat101Second);
    }

    @Test
    void markPendingSupersededPersistsSupersededStatusAndTouchesOnlyOwner() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long x = template.execute(status ->
                repository.save(ActionAudit.pending(OWNER_X, ACTION_TYPE, "{\"seatId\":101}", SUPERSEDE_AT)).getId());
        Long y = template.execute(status ->
                repository.save(ActionAudit.pending(OWNER_Y, ACTION_TYPE, "{\"seatId\":201}", SUPERSEDE_AT)).getId());

        int changed = template.execute(status -> repository.markPendingSuperseded(OWNER_X, SUPERSEDE_AT));

        assertThat(changed).isEqualTo(1);
        assertThat(repository.findById(x).orElseThrow().getStatus()).isEqualTo(ActionStatus.SUPERSEDED);
        assertThat(repository.findById(y).orElseThrow().getStatus()).isEqualTo(ActionStatus.PENDING);
    }

    @Test
    void lockByIdAndStudentIdAndStatusEnforcesOwnership() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long idX = template.execute(status ->
                repository.save(ActionAudit.pending(OWNER_X, ACTION_TYPE, "{\"seatId\":101}", SUPERSEDE_AT)).getId());

        // The owning caller (X) finds the row; a different caller (Y) presenting the same id
        // finds nothing — the studentId predicate in the WHERE clause is doing the filtering.
        template.executeWithoutResult(status -> {
            List<ActionAudit> ownerHit = repository.lockByIdAndStudentIdAndStatus(
                    idX, OWNER_X, ActionStatus.PENDING, PageRequest.of(0, 1));
            List<ActionAudit> foreignMiss = repository.lockByIdAndStudentIdAndStatus(
                    idX, OWNER_Y, ActionStatus.PENDING, PageRequest.of(0, 1));

            assertThat(ownerHit).extracting(ActionAudit::getId).containsExactly(idX);
            assertThat(foreignMiss).isEmpty();
        });
    }

    @Test
    void lockByIdAndStudentIdAndStatusMissesNonPendingRow() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long idX = template.execute(status -> {
            ActionAudit a = ActionAudit.pending(OWNER_X, ACTION_TYPE, "{\"seatId\":101}", SUPERSEDE_AT);
            a.supersede(SUPERSEDE_AT); // now SUPERSEDED, must never be claimable
            return repository.save(a).getId();
        });

        template.executeWithoutResult(status -> {
            List<ActionAudit> hit = repository.lockByIdAndStudentIdAndStatus(
                    idX, OWNER_X, ActionStatus.PENDING, PageRequest.of(0, 1));
            assertThat(hit).isEmpty();
        });
    }
}
