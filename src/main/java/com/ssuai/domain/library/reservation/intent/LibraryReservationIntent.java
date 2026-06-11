package com.ssuai.domain.library.reservation.intent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "library_reservation_intents")
public class LibraryReservationIntent {

    private static final Set<LibraryReservationIntentStatus> TERMINAL_STATUSES = Set.of(
            LibraryReservationIntentStatus.SUCCEEDED,
            LibraryReservationIntentStatus.FAILED_RACE,
            LibraryReservationIntentStatus.FAILED_AUTH,
            LibraryReservationIntentStatus.FAILED_UPSTREAM,
            LibraryReservationIntentStatus.CANCELLED,
            LibraryReservationIntentStatus.EXPIRED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(name = "session_key", length = 128, nullable = false)
    private String sessionKey;

    @Column(name = "preferred_floor", length = 8)
    private String preferredFloor;

    @Column(name = "preferred_room_ids", columnDefinition = "TEXT")
    private String preferredRoomIds;

    @Column(name = "seat_attributes", columnDefinition = "TEXT")
    private String seatAttributes;

    @Column(name = "target_seat_id")
    private Long targetSeatId;

    @Column(name = "status", length = 32, nullable = false)
    @Enumerated(EnumType.STRING)
    private LibraryReservationIntentStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "outcome_code", length = 32)
    private String outcomeCode;

    @Column(name = "outcome_message", columnDefinition = "TEXT")
    private String outcomeMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LibraryReservationIntent() {
        // JPA
    }

    private LibraryReservationIntent(
            String studentId,
            String sessionKey,
            String preferredFloor,
            String preferredRoomIds,
            String seatAttributes,
            Long targetSeatId,
            Instant now,
            Instant expiresAt) {
        this.studentId = requireNonBlank(studentId, "studentId");
        this.sessionKey = requireNonBlank(sessionKey, "sessionKey");
        this.preferredFloor = blankToNull(preferredFloor);
        this.preferredRoomIds = blankToNull(preferredRoomIds);
        this.seatAttributes = blankToNull(seatAttributes);
        this.targetSeatId = targetSeatId;
        this.status = LibraryReservationIntentStatus.REQUESTED;
        this.attemptCount = 0;
        this.nextAttemptAt = Objects.requireNonNull(now, "now");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static LibraryReservationIntent requested(
            String studentId,
            String sessionKey,
            String preferredFloor,
            String preferredRoomIds,
            String seatAttributes,
            Long targetSeatId,
            Instant now,
            Instant expiresAt) {
        return new LibraryReservationIntent(
                studentId, sessionKey, preferredFloor, preferredRoomIds, seatAttributes, targetSeatId, now, expiresAt);
    }

    public void markWaitingForSeat(Instant now) {
        requireStatus(LibraryReservationIntentStatus.REQUESTED);
        this.status = LibraryReservationIntentStatus.WAITING_FOR_SEAT;
        this.nextAttemptAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void claimForReservation(Instant now, Duration leaseDuration) {
        requireStatus(LibraryReservationIntentStatus.WAITING_FOR_SEAT);
        this.status = LibraryReservationIntentStatus.RESERVING;
        this.lockedUntil = Objects.requireNonNull(now, "now").plus(Objects.requireNonNull(leaseDuration, "leaseDuration"));
        this.updatedAt = now;
    }

    public void extendLeaseForReaper(Instant now, Duration leaseDuration) {
        requireStatus(LibraryReservationIntentStatus.RESERVING);
        this.lockedUntil = Objects.requireNonNull(now, "now").plus(Objects.requireNonNull(leaseDuration, "leaseDuration"));
        this.updatedAt = now;
    }

    public void returnToWaiting(Instant now, Duration backoff, String message) {
        requireStatus(LibraryReservationIntentStatus.RESERVING);
        this.status = LibraryReservationIntentStatus.WAITING_FOR_SEAT;
        this.attemptCount += 1;
        this.nextAttemptAt = Objects.requireNonNull(now, "now").plus(Objects.requireNonNull(backoff, "backoff"));
        this.lockedUntil = null;
        this.outcomeCode = "NO_SEAT_AVAILABLE";
        this.outcomeMessage = message;
        this.updatedAt = now;
    }

    public void succeed(Instant now, String message) {
        complete(LibraryReservationIntentStatus.SUCCEEDED, "SUCCESS", message, now);
    }

    public void failRace(Instant now, String message) {
        complete(LibraryReservationIntentStatus.FAILED_RACE, "FAILED_RACE", message, now);
    }

    public void failAuth(Instant now, String message) {
        complete(LibraryReservationIntentStatus.FAILED_AUTH, "FAILED_AUTH", message, now);
    }

    public void failUpstream(Instant now, String message) {
        complete(LibraryReservationIntentStatus.FAILED_UPSTREAM, "FAILED_UPSTREAM", message, now);
    }

    public void cancel(Instant now, String message) {
        if (status != LibraryReservationIntentStatus.REQUESTED
                && status != LibraryReservationIntentStatus.WAITING_FOR_SEAT) {
            throw new IllegalStateException("Only requested or waiting intents can be cancelled.");
        }
        complete(LibraryReservationIntentStatus.CANCELLED, "CANCELLED", message, now);
    }

    public void expire(Instant now, String message) {
        if (isTerminal()) {
            return;
        }
        complete(LibraryReservationIntentStatus.EXPIRED, "EXPIRED", message, now);
    }

    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(status);
    }

    public boolean isActive() {
        return !isTerminal();
    }

    private void complete(
            LibraryReservationIntentStatus terminalStatus,
            String outcomeCode,
            String outcomeMessage,
            Instant completedAt) {
        if (isTerminal()) {
            throw new IllegalStateException("Intent is already terminal.");
        }
        this.status = Objects.requireNonNull(terminalStatus, "terminalStatus");
        this.outcomeCode = requireNonBlank(outcomeCode, "outcomeCode");
        this.outcomeMessage = outcomeMessage;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.lockedUntil = null;
        this.updatedAt = completedAt;
    }

    private void requireStatus(LibraryReservationIntentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected status " + expected + " but was " + status);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public String getPreferredFloor() {
        return preferredFloor;
    }

    public String getPreferredRoomIds() {
        return preferredRoomIds;
    }

    public String getSeatAttributes() {
        return seatAttributes;
    }

    public Long getTargetSeatId() {
        return targetSeatId;
    }

    public LibraryReservationIntentStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getOutcomeCode() {
        return outcomeCode;
    }

    public String getOutcomeMessage() {
        return outcomeMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
