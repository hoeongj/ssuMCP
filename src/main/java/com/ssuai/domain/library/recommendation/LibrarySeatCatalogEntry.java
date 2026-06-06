package com.ssuai.domain.library.recommendation;

import com.ssuai.domain.library.dto.LibraryFloor;

public record LibrarySeatCatalogEntry(
        int floor,
        String roomCode,
        String roomName,
        String seatId,
        String externalSeatId,
        String label,
        String zone,
        String seatType,
        String audience,
        LibrarySeatAttributes attributes,
        String note
) {

    public LibrarySeatCatalogEntry {
        if (floor <= 0) {
            throw new IllegalArgumentException("floor must be positive");
        }
        roomCode = requireText(roomCode, "roomCode");
        roomName = requireText(roomName, "roomName");
        seatId = requireText(seatId, "seatId");
        externalSeatId = blankToNull(externalSeatId);
        label = blankToNull(label);
        zone = requireText(zone, "zone");
        seatType = blankToNull(seatType);
        audience = blankToNull(audience);
        attributes = attributes == null
                ? new LibrarySeatAttributes(false, false, false, false, false, false)
                : attributes;
        note = blankToNull(note);
    }

    public boolean belongsTo(LibraryFloor targetFloor) {
        return targetFloor != null && floor == targetFloor.code();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
