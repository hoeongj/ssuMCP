package com.ssuai.domain.library.reservation.intent;

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
@Table(name = "library_reservation_outbox")
public class LibraryReservationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 64, nullable = false)
    @Enumerated(EnumType.STRING)
    private LibraryReservationIntentEventType eventType;

    @Column(name = "intent_id", nullable = false)
    private Long intentId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 128)
    private String claimedBy;

    protected LibraryReservationOutbox() {
        // JPA
    }

    public LibraryReservationOutbox(
            LibraryReservationIntentEventType eventType,
            Long intentId,
            String payload,
            Instant createdAt) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.intentId = Objects.requireNonNull(intentId, "intentId");
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload cannot be blank");
        }
        this.payload = payload;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public void markPublished(Instant now) {
        this.publishedAt = Objects.requireNonNull(now, "now");
        this.claimedAt = null;
        this.claimedBy = null;
    }

    public void claim(String owner, Instant now) {
        this.claimedBy = requireNonBlank(owner, "owner");
        this.claimedAt = Objects.requireNonNull(now, "now");
    }

    public Long getId() {
        return id;
    }

    public LibraryReservationIntentEventType getEventType() {
        return eventType;
    }

    public Long getIntentId() {
        return intentId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
