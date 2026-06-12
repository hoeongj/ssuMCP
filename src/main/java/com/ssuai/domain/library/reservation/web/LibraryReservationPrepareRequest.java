package com.ssuai.domain.library.reservation.web;

public record LibraryReservationPrepareRequest(
        String type,
        Long seatId,
        Long targetSeatId
) {
}
