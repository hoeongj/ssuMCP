package com.ssuai.domain.library.reservation;

public record LibraryReservationResult(
        long chargeId,
        String roomName,
        String seatCode,
        String beginTime,
        String endTime,
        Integer roomId,
        Long seatId
) {

    public LibraryReservationResult(
            long chargeId,
            String roomName,
            String seatCode,
            String beginTime,
            String endTime) {
        this(chargeId, roomName, seatCode, beginTime, endTime, null, null);
    }
}
