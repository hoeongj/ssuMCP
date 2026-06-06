package com.ssuai.domain.library.dto;

/**
 * Per-seat snapshot from GET /pyxis-api/1/api/rooms/{roomId}/seats.
 *
 * status values:
 *   available — isActive=true, isOccupied=false
 *   occupied  — isActive=true, isOccupied=true, seatChargeState=CHARGE
 *   away      — isActive=true, isOccupied=true, seatChargeState=TEMP_CHARGE (이석)
 *   inactive  — isActive=false (seat disabled)
 *
 * remainingTime is non-zero only for "away" seats; indicates minutes until auto-return.
 */
public record PyxisSeatInfo(
        int externalSeatId,
        String label,
        String seatType,
        String status,
        int remainingTime,
        int chargeTime
) {}
