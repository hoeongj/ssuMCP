package com.ssuai.domain.library.recommendation;

import java.util.List;

public record LibrarySeatRoomCatalogEntry(
        String floorCode,
        Integer floor,
        String roomCode,
        String roomName,
        String audience,
        boolean reservable,
        boolean graduateOnly,
        boolean containsFreeUseSeats,
        String seatIdPattern,
        List<String> seatTypes,
        List<String> zones,
        List<String> textLayout,
        List<String> captureNotes
) {

    public LibrarySeatRoomCatalogEntry {
        floorCode = requireText(floorCode, "floorCode");
        roomCode = requireText(roomCode, "roomCode");
        roomName = requireText(roomName, "roomName");
        audience = audience == null || audience.isBlank() ? "all" : audience.trim();
        seatIdPattern = seatIdPattern == null || seatIdPattern.isBlank() ? null : seatIdPattern.trim();
        seatTypes = seatTypes == null ? List.of() : List.copyOf(seatTypes);
        zones = zones == null ? List.of() : List.copyOf(zones);
        textLayout = textLayout == null ? List.of() : List.copyOf(textLayout);
        captureNotes = captureNotes == null ? List.of() : List.copyOf(captureNotes);
    }

    public LibrarySeatRoomCatalogEntry withoutLayout() {
        return new LibrarySeatRoomCatalogEntry(
                floorCode,
                floor,
                roomCode,
                roomName,
                audience,
                reservable,
                graduateOnly,
                containsFreeUseSeats,
                seatIdPattern,
                seatTypes,
                zones,
                List.of(),
                captureNotes);
    }

    /** captureNotes are internal data-collection TODOs, not user-facing room metadata. */
    public LibrarySeatRoomCatalogEntry withoutCaptureNotes() {
        return new LibrarySeatRoomCatalogEntry(
                floorCode,
                floor,
                roomCode,
                roomName,
                audience,
                reservable,
                graduateOnly,
                containsFreeUseSeats,
                seatIdPattern,
                seatTypes,
                zones,
                textLayout,
                List.of());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
