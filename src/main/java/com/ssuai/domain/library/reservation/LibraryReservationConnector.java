package com.ssuai.domain.library.reservation;

import java.util.Optional;

public interface LibraryReservationConnector {

    Optional<LibraryReservationResult> getCurrentCharge(String pyxisAuthToken);

    LibraryReservationResult reserve(String pyxisAuthToken, LibraryReservationRequest request);

    void discharge(String pyxisAuthToken, long chargeId);
}
