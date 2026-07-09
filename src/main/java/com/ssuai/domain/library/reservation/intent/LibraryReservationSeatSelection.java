package com.ssuai.domain.library.reservation.intent;

public record LibraryReservationSeatSelection(long seatId, int roomId) {

    public LibraryReservationSeatSelection {
        if (seatId <= 0) {
            throw new IllegalArgumentException("seatId must be positive");
        }
        if (roomId <= 0) {
            throw new IllegalArgumentException("roomId must be positive");
        }
    }
}
