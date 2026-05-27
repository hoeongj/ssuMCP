package com.ssuai.domain.library.connector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;

class MockLibrarySeatConnectorTests {

    private final MockLibrarySeatConnector connector = new MockLibrarySeatConnector();

    @ParameterizedTest
    @EnumSource(LibraryFloor.class)
    void everyFloorReturnsConsistentSnapshot(LibraryFloor floor) {
        LibrarySeatStatusResponse response = connector.fetchSeatStatus(floor, null);

        assertThat(response.floor()).isEqualTo(floor.code());
        assertThat(response.floorLabel()).isEqualTo(floor.displayLabel());
        assertThat(response.totalSeats()).isPositive();
        assertThat(response.availableSeats()).isNotNegative();
        assertThat(response.reservedSeats()).isNotNegative();
        assertThat(response.outOfServiceSeats()).isNotNegative();
        assertThat(response.fetchedAt()).isNotNull();
        assertThat(response.zones()).isNotEmpty();
        int zoneTotal = response.zones().stream()
                .mapToInt(LibrarySeatZone::total)
                .sum();
        assertThat(zoneTotal).isEqualTo(response.totalSeats());
        int zoneAvailable = response.zones().stream()
                .mapToInt(LibrarySeatZone::available)
                .sum();
        assertThat(zoneAvailable).isEqualTo(response.availableSeats());
        assertThat(response.zones()).allSatisfy(zone -> {
            assertThat(zone.seats()).hasSize(zone.total());
            assertThat(zone.seatIds()).hasSize(zone.available());
            assertThat(zone.seats().stream()
                    .filter(seat -> "available".equals(seat.status())))
                    .hasSize(zone.available());
        });
    }

    @Test
    void allFloorsReturnNonEmptyZones() {
        for (LibraryFloor floor : LibraryFloor.values()) {
            LibrarySeatStatusResponse response = connector.fetchSeatStatus(floor, null);
            assertThat(response.zones())
                    .as("floor %s should have at least one zone", floor)
                    .isNotEmpty();
        }
    }
}
