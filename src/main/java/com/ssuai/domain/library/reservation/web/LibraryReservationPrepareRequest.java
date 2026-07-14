package com.ssuai.domain.library.reservation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LibraryReservationPrepareRequest(
        @NotBlank @Size(max = 16) String type,
        @Positive Long seatId,
        @Positive Long targetSeatId
) {
}
