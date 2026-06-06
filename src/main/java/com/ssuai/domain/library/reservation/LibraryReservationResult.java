package com.ssuai.domain.library.reservation;

public record LibraryReservationResult(
        long chargeId,
        String roomName,
        String seatCode,
        String beginTime,
        String endTime
) {
}
