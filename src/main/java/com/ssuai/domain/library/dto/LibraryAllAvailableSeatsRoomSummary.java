package com.ssuai.domain.library.dto;

import java.util.List;

public record LibraryAllAvailableSeatsRoomSummary(
        int roomId,
        String roomName,
        int totalSeats,
        int availableSeats,
        int awaySeats,
        List<Integer> availableExternalSeatIds,
        List<String> availableLabels
) {
    public LibraryAllAvailableSeatsRoomSummary {
        availableExternalSeatIds = availableExternalSeatIds == null
                ? List.of() : List.copyOf(availableExternalSeatIds);
        availableLabels = availableLabels == null ? List.of() : List.copyOf(availableLabels);
    }
}
