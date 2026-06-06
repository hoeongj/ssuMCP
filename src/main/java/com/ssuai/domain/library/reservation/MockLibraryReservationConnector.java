package com.ssuai.domain.library.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ssuai.connector.library-reservation", havingValue = "mock", matchIfMissing = true)
public class MockLibraryReservationConnector implements LibraryReservationConnector {

    @Override
    public java.util.Optional<LibraryReservationResult> getCurrentCharge(String pyxisAuthToken) {
        return java.util.Optional.empty();
    }

    @Override
    public LibraryReservationResult reserve(String pyxisAuthToken, LibraryReservationRequest request) {
        return new LibraryReservationResult(0L, "열람실(Mock)", "0", "00:00", "00:00");
    }

    @Override
    public void discharge(String pyxisAuthToken, long chargeId) {
        // Mock mode intentionally does not call oasis.
    }
}
