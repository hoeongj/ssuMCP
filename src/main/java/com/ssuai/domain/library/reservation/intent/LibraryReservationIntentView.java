package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;

public record LibraryReservationIntentView(
        Long intentId,
        LibraryReservationIntentStatus status,
        Integer attemptCount,
        Instant nextAttemptAt,
        Instant expiresAt,
        Instant completedAt,
        String outcomeCode,
        String outcomeMessage,
        Long targetSeatId
) {

    public static LibraryReservationIntentView from(LibraryReservationIntent intent) {
        return new LibraryReservationIntentView(
                intent.getId(),
                intent.getStatus(),
                intent.getAttemptCount(),
                intent.getNextAttemptAt(),
                intent.getExpiresAt(),
                intent.getCompletedAt(),
                intent.getOutcomeCode(),
                intent.getOutcomeMessage(),
                intent.getTargetSeatId());
    }
}
