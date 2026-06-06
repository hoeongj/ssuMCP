package com.ssuai.domain.library.dto;

import java.time.Instant;
import java.util.List;

public record LibraryRoomAvailableSeatsResponse(
        int roomId,
        String roomName,
        int totalSeats,
        int availableSeats,
        int occupiedSeats,
        int awaySeats,
        int inactiveSeats,
        Instant fetchedAt,
        List<PyxisSeatInfo> seats
) {
    public LibraryRoomAvailableSeatsResponse {
        seats = seats == null ? List.of() : List.copyOf(seats);
    }
}
