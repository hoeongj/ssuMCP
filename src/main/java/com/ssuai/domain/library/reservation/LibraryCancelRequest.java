package com.ssuai.domain.library.reservation;

public record LibraryCancelRequest(long chargeId, Integer roomId, Long seatId) {

    public LibraryCancelRequest(long chargeId) {
        this(chargeId, null, null);
    }
}
