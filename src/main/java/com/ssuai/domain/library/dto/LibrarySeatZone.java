package com.ssuai.domain.library.dto;

import java.util.List;

public record LibrarySeatZone(
        String label,
        int total,
        int available,
        List<String> seatIds,
        List<LibrarySeatItem> seats
) {

    public LibrarySeatZone {
        seatIds = seatIds == null ? List.of() : List.copyOf(seatIds);
        seats = seats == null ? List.of() : List.copyOf(seats);
    }
}
