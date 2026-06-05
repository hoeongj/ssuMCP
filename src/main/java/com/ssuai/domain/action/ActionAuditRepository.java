package com.ssuai.domain.action;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionAuditRepository extends JpaRepository<ActionAudit, Long> {

    Optional<ActionAudit> findTopByStudentIdAndStatusOrderByCreatedAtDesc(String studentId, ActionStatus status);

    List<ActionAudit> findAllByStatusAndCreatedAtBefore(ActionStatus status, Instant cutoff);
}
