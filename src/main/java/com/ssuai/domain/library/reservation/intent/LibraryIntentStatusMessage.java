package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;

public record LibraryIntentStatusMessage(
        Long intentId,
        LibraryReservationIntentEventType eventType,
        Instant timestamp) {
}
