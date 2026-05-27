package com.ssuai.domain.library.dto;

import java.time.Instant;
import java.util.List;

public record LibrarySeatStatusResponse(
        int floor,
        String floorLabel,
        int totalSeats,
        int availableSeats,
        int reservedSeats,
        int outOfServiceSeats,
        Instant fetchedAt,
        List<LibrarySeatZone> zones
) {

    public LibrarySeatStatusResponse {
        zones = zones == null ? List.of() : List.copyOf(zones);
    }
}
