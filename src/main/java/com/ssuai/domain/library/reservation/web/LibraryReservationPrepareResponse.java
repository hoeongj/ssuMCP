package com.ssuai.domain.library.reservation.web;

import java.time.Instant;

public record LibraryReservationPrepareResponse(
        Long actionId,
        String actionType,
        String summary,
        Instant expiresAt
) {
}
