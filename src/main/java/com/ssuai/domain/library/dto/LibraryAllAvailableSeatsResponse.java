package com.ssuai.domain.library.dto;

import java.time.Instant;
import java.util.List;

public record LibraryAllAvailableSeatsResponse(
        int totalAvailableSeats,
        int totalAwaySeats,
        Instant fetchedAt,
        List<LibraryAllAvailableSeatsRoomSummary> rooms
) {
    public LibraryAllAvailableSeatsResponse {
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }
}
