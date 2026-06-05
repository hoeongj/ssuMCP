package com.ssuai.domain.library.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ssuai.connector.library-reservation", havingValue = "mock", matchIfMissing = true)
public class MockLibraryReservationConnector implements LibraryReservationConnector {

    @Override
    public void reserve(String pyxisAuthToken, LibraryReservationRequest request) {
        // Mock mode intentionally does not call oasis.
    }
}
