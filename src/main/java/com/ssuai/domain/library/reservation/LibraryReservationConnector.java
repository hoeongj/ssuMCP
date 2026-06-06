package com.ssuai.domain.library.reservation;

public interface LibraryReservationConnector {

    LibraryReservationResult reserve(String pyxisAuthToken, LibraryReservationRequest request);

    void discharge(String pyxisAuthToken, long chargeId);
}
