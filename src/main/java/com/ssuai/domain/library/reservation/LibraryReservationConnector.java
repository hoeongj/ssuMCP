package com.ssuai.domain.library.reservation;

public interface LibraryReservationConnector {

    void reserve(String pyxisAuthToken, LibraryReservationRequest request);
}
