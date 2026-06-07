package com.ssuai.domain.action;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionAuditRepository extends JpaRepository<ActionAudit, Long> {

    Optional<ActionAudit> findTopByStudentIdAndStatusOrderByCreatedAtDesc(String studentId, ActionStatus status);

    List<ActionAudit> findAllByStatusAndCreatedAtBefore(ActionStatus status, Instant cutoff);

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
}
