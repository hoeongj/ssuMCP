package com.ssuai.domain.library.reservation.web;

public record LibraryReservationConfirmResponse(
        String status,
        Long intentId,
        String message
) {
}
