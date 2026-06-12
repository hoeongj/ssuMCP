package com.ssuai.domain.library.events;

import java.time.Instant;

public record LibrarySeatEvent(
        int schemaVersion,
        Integer roomId,
        Long seatId,
        LibrarySeatEventAction action,
        Instant occurredAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public LibrarySeatEvent {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
    }

    public static LibrarySeatEvent v1(
            Integer roomId,
            Long seatId,
            LibrarySeatEventAction action,
            Instant occurredAt) {
        return new LibrarySeatEvent(CURRENT_SCHEMA_VERSION, roomId, seatId, action, occurredAt);
    }
}
