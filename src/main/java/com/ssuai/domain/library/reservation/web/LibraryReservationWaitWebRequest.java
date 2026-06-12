package com.ssuai.domain.library.reservation.web;

public record LibraryReservationWaitWebRequest(
        String preferredFloor,
        String preferredRoomIds,
        String seatAttributes,
        Long targetSeatId
) {
}
