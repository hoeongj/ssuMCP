package com.ssuai.domain.library.reservation.intent;

public enum LibraryReservationIntentStatus {
    REQUESTED,
    WAITING_FOR_SEAT,
    RESERVING,
    SUCCEEDED,
    FAILED_RACE,
    FAILED_AUTH,
    FAILED_UPSTREAM,
    CANCELLED,
    EXPIRED
}
