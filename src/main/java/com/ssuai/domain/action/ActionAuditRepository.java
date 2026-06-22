package com.ssuai.domain.action;

import java.time.Instant;
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
     * single UPDATE (ADR 0055). Called inside the prepare transaction <em>before</em>
     * inserting the new PENDING row, so an owner is left with at most one active PENDING
     * action and a later confirm can never execute a stale, never-re-approved request.
     * Reuses {@code expired_at} as the made-inert timestamp. Returns the rows changed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ActionAudit a set a.status = com.ssuai.domain.action.ActionStatus.SUPERSEDED, "
            + "a.expiredAt = :supersededAt "
            + "where a.studentId = :studentId and a.status = com.ssuai.domain.action.ActionStatus.PENDING")
    int markPendingSuperseded(@Param("studentId") String studentId,
                              @Param("supersededAt") Instant supersededAt);

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
