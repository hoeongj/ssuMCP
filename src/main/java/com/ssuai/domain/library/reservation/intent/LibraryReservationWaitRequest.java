package com.ssuai.domain.library.reservation.intent;

import java.time.Duration;

public record LibraryReservationWaitRequest(
        String preferredFloor,
        String preferredRoomIds,
        String seatAttributes,
        Long targetSeatId,
        Duration expiresIn
) {
}
