package com.ssuai.domain.library.reservation.web;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LibraryReservationWaitWebRequest(
        @Size(max = 16) String preferredFloor,
        @Size(max = 512) String preferredRoomIds,
        @Size(max = 512) String seatAttributes,
        @Positive Long targetSeatId
) {
}
