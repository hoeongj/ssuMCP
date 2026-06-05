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

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

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

    public void confirm(Instant confirmedAt) {
        if (status != ActionStatus.PENDING) {
            throw new IllegalStateException("Only PENDING actions can be confirmed.");
        }
        this.status = ActionStatus.CONFIRMED;
        this.confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
