package com.ssuai.domain.library.reservation;

public record LibrarySwapRequest(long oldChargeId, long newSeatId, Integer oldRoomId, Long oldSeatId) {

    public LibrarySwapRequest(long oldChargeId, long newSeatId) {
        this(oldChargeId, newSeatId, null, null);
    }
}
