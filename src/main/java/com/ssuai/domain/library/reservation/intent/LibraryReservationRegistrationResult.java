package com.ssuai.domain.library.reservation.intent;

public record LibraryReservationRegistrationResult(
        LibraryReservationIntentView intent,
        boolean newlyCreated
) {
}
