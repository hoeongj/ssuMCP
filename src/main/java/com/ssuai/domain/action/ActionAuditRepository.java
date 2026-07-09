package com.ssuai.domain.action;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionAuditRepository extends JpaRepository<ActionAudit, Long> {

    Optional<ActionAudit> findTopByStudentIdAndStatusOrderByCreatedAtDesc(String studentId, ActionStatus status);

    List<ActionAudit> findAllByStudentIdAndStatus(String studentId, ActionStatus status);

    List<ActionAudit> findAllByStatusAndCreatedAtBefore(ActionStatus status, Instant cutoff);

    /**
     * Atomically marks every still-PENDING action of {@code studentId} as SUPERSEDED in a
     * single UPDATE, REGARDLESS of {@code actionType}/{@code targetKey} (ADR 0055; scope
     * intentionally left owner-wide here — see ADR 0086). Used only by the legacy,
     * non-{@code action_id} confirm callers ({@code LibraryReservationWebController},
     * {@code LmsMaterialExportService.confirm}) that pick "the most recent PENDING action" with
     * no disambiguation: those callers still require the ADR 0055 invariant of at most one
     * active PENDING action per owner at any time, across every action type. MCP prepare tools
     * (which support explicit {@code action_id} confirm targeting) use the narrower
     * {@link #markPendingSupersededForAction} instead so unrelated concurrent actions of the
     * same owner (e.g. two different pending seat reservations) no longer invalidate each other.
     * Reuses {@code expired_at} as the made-inert timestamp. Returns the rows changed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActionAudit a set a.status = com.ssuai.domain.action.ActionStatus.SUPERSEDED, "
            + "a.expiredAt = :supersededAt "
            + "where a.studentId = :studentId and a.status = com.ssuai.domain.action.ActionStatus.PENDING")
    int markPendingSuperseded(@Param("studentId") String studentId,
                              @Param("supersededAt") Instant supersededAt);

    /**
     * Atomically marks PENDING actions of {@code studentId} as SUPERSEDED, scoped to the same
     * {@code actionType} AND {@code targetKey} (ADR 0086, narrowing ADR 0055's owner-wide scope).
     * Called inside the prepare transaction <em>before</em> inserting the new PENDING row, so a
     * re-prepare of the SAME action (e.g. the same seat id, the same charge id) replaces its own
     * predecessor while a DIFFERENT concurrent action of the same owner is left untouched.
     * Reuses {@code expired_at} as the made-inert timestamp. Returns the rows changed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActionAudit a set a.status = com.ssuai.domain.action.ActionStatus.SUPERSEDED, "
            + "a.expiredAt = :supersededAt "
            + "where a.studentId = :studentId and a.actionType = :actionType and a.targetKey = :targetKey "
            + "and a.status = com.ssuai.domain.action.ActionStatus.PENDING")
    int markPendingSupersededForAction(@Param("studentId") String studentId,
                              @Param("actionType") String actionType,
                              @Param("targetKey") String targetKey,
                              @Param("supersededAt") Instant supersededAt);

    /**
     * Retention sweep (ADR 0072): bulk-deletes rows that are BOTH in a terminal status AND
     * older than the cutoff, in a single DELETE statement (no entity hydration). Callers pass
     * only terminal statuses ({@code SUCCESS}/{@code FAILED}/{@code EXPIRED}/{@code SUPERSEDED});
     * PENDING and EXECUTING rows are never eligible regardless of age — the status predicate,
     * not the age predicate, is the safety boundary. Returns the rows deleted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ActionAudit a where a.status in :statuses and a.createdAt < :cutoff")
    int deleteByStatusInAndCreatedAtBefore(@Param("statuses") Collection<ActionStatus> statuses,
                                           @Param("cutoff") Instant cutoff);

    /**
     * Row-locking variant used by confirm_action: takes a pessimistic write lock
     * (SELECT ... FOR UPDATE) on the most recent matching row so two concurrent
     * confirms serialize on the claim instead of both executing the same action.
     * Call with {@code PageRequest.of(0, 1)} and read the single element.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActionAudit a where a.studentId = :studentId and a.status = :status order by a.createdAt desc")
    List<ActionAudit> lockByStudentIdAndStatus(@Param("studentId") String studentId,
                                               @Param("status") ActionStatus status,
                                               Pageable pageable);

    /**
     * Ownership-enforcing locked claim for explicit-id confirm: takes a pessimistic write
     * lock on the row only when it matches the action id <em>and</em> the caller's owner key
     * <em>and</em> is still PENDING. A wrong owner, a non-PENDING (already executed /
     * superseded / expired) row, or an unknown id all yield an empty result under the lock —
     * the caller must then deny, never fall back to confirming a different action.
     * Call with {@code PageRequest.of(0, 1)} and read the single element.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActionAudit a where a.id = :id and a.studentId = :studentId and a.status = :status")
    List<ActionAudit> lockByIdAndStudentIdAndStatus(@Param("id") Long id,
                                                    @Param("studentId") String studentId,
                                                    @Param("status") ActionStatus status,
                                                    Pageable pageable);
}
