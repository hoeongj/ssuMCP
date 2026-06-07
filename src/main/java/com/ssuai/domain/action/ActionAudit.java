package com.ssuai.domain.action;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "action_audit")
public class ActionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(name = "action_type", length = 64, nullable = false)
    private String actionType;

    @Column(name = "status", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private ActionStatus status;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** When the action was claimed for execution (PENDING -> EXECUTING). */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    /** Terminal outcome detail: SUCCESS | FAILURE_RACE | FAILURE_AUTH | FAILURE_UPSTREAM | TIMEOUT. */
    @Column(name = "outcome_code", length = 32)
    private String outcomeCode;

    @Column(name = "outcome_message", columnDefinition = "TEXT")
    private String outcomeMessage;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ActionAudit() {
        // JPA
    }

    private ActionAudit(String studentId, String actionType, ActionStatus status, String payload, Instant createdAt) {
        this.studentId = requireNonBlank(studentId, "studentId");
        this.actionType = requireNonBlank(actionType, "actionType");
        this.status = Objects.requireNonNull(status, "status");
        this.payload = requireNonBlank(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ActionAudit pending(String studentId, String actionType, String payload, Instant createdAt) {
        return new ActionAudit(studentId, actionType, ActionStatus.PENDING, payload, createdAt);
    }

    /** Claims a PENDING action for execution (PENDING -> EXECUTING). */
    public void markExecuting(Instant startedAt) {
        if (status != ActionStatus.PENDING) {
            throw new IllegalStateException("Only PENDING actions can start executing.");
        }
        this.status = ActionStatus.EXECUTING;
        this.confirmedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * Records the terminal outcome (EXECUTING -> SUCCESS/FAILED). {@code outcomeCode}
     * "SUCCESS" maps to {@link ActionStatus#SUCCESS}; any other code maps to FAILED.
     */
    public void complete(String outcomeCode, String outcomeMessage, Instant completedAt) {
        if (status != ActionStatus.EXECUTING) {
            throw new IllegalStateException("Only EXECUTING actions can be completed.");
        }
        this.outcomeCode = requireNonBlank(outcomeCode, "outcomeCode");
        this.outcomeMessage = outcomeMessage;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.status = "SUCCESS".equals(outcomeCode) ? ActionStatus.SUCCESS : ActionStatus.FAILED;
    }

    public void expire(Instant expiredAt) {
        if (status != ActionStatus.PENDING) {
            return;
        }
        this.status = ActionStatus.EXPIRED;
        this.expiredAt = Objects.requireNonNull(expiredAt, "expiredAt");
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getActionType() {
        return actionType;
    }

    public ActionStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public String getOutcomeCode() {
        return outcomeCode;
    }

    public String getOutcomeMessage() {
        return outcomeMessage;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
