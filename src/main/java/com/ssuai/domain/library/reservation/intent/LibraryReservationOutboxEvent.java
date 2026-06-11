package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;

public record LibraryReservationOutboxEvent(
        Long outboxId,
        LibraryReservationIntentEventType eventType,
        Long intentId,
        String payload,
        Instant createdAt
) {
}
