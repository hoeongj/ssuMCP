package com.ssuai.domain.library.timeseries;

import java.time.Instant;

public record LibrarySeatSample(
        Instant sampledAt,
        int roomId,
        int externalSeatId,
        String seatLabel,
        String seatType,
        String statusCode,
        int remainingTimeMinutes,
        int chargeTimeMinutes,
        Instant createdAt
) {

    public LibrarySeatSample {
        if (sampledAt == null) {
            throw new IllegalArgumentException("sampledAt is required");
        }
        if (externalSeatId <= 0) {
            throw new IllegalArgumentException("externalSeatId must be positive");
        }
        seatLabel = seatLabel == null || seatLabel.isBlank()
                ? Integer.toString(externalSeatId)
                : seatLabel.trim();
        seatType = seatType == null || seatType.isBlank() ? null : seatType.trim();
        if (statusCode == null || statusCode.isBlank()) {
            throw new IllegalArgumentException("statusCode is required");
        }
        statusCode = statusCode.trim();
        remainingTimeMinutes = Math.max(remainingTimeMinutes, 0);
        chargeTimeMinutes = Math.max(chargeTimeMinutes, 0);
        createdAt = createdAt == null ? sampledAt : createdAt;
    }
}
