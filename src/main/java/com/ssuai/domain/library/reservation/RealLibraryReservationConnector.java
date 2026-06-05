package com.ssuai.domain.library.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ssuai.connector.library-reservation", havingValue = "real")
public class RealLibraryReservationConnector implements LibraryReservationConnector {

    @Override
    public void reserve(String pyxisAuthToken, LibraryReservationRequest request) {
        throw new UnsupportedOperationException("oasis 예약 API 스파이크 필요");
    }
}
