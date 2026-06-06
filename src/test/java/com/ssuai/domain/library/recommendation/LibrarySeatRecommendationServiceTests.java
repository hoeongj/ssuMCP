package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatItem;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.service.LibrarySeatService;

class LibrarySeatRecommendationServiceTests {

    private static final String SESSION_KEY = "library-session-key";

    private LibrarySeatService seatService;
    private LibrarySeatRecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        seatService = mock(LibrarySeatService.class);
        recommendationService = new LibrarySeatRecommendationService(
                seatService, new LibrarySeatCatalogService());
    }

    @Test
    void recommendsOnlyCurrentlyAvailableCatalogedSeats() {
        when(seatService.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .thenReturn(statusWithSeatItems(List.of(
                        new LibrarySeatItem("1", "1", "available"),
                        new LibrarySeatItem("2", "2", "available"),
                        new LibrarySeatItem("76", "76", "occupied"),
                        new LibrarySeatItem("101", "101", "available")
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(null, null, false, null, true, null),
                10);

        assertThat(response.availabilitySource()).isEqualTo("live_seat_items");
        assertThat(response.liveAvailableSeats()).isEqualTo(3);
        assertThat(response.liveSeatItemsSeen()).isEqualTo(4);
        assertThat(response.catalogMatchedAvailableSeats()).isEqualTo(3);
        assertThat(response.recommendations())
                .extracting(LibrarySeatRecommendation::seatId)
                .containsExactly("1", "2", "101");
        assertThat(response.recommendations())
                .noneMatch(recommendation -> recommendation.seatId().equals("76"));
        assertThat(response.recommendations().getFirst().matchedPreferences())
                .contains("not_standing", "quiet");
    }

    @Test
    void usesAvailableSeatIdsWhenConnectorDoesNotExposeSeatItems() {
        when(seatService.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .thenReturn(statusWithSeatIds(List.of("10", "1")));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(null, null, null, null, null, null),
                2);

        assertThat(response.availabilitySource()).isEqualTo("live_available_seat_ids");
        assertThat(response.recommendations())
                .extracting(LibrarySeatRecommendation::seatId)
                .containsExactly("1", "10");
    }

    @Test
    void returnsNoRecommendationWhenOnlyFloorLevelAvailabilityExists() {
        when(seatService.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .thenReturn(statusWithoutSeatIds());

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(true, true, null, null, null, null),
                null);

        assertThat(response.requestedLimit()).isEqualTo(5);
        assertThat(response.availabilitySource()).isEqualTo("floor_only");
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.message()).contains("Pyxis seat-map API");
    }

    @Test
    void clampsLimitToTen() {
        when(seatService.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .thenReturn(statusWithSeatIds(List.of("1")));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(null, null, null, null, null, null),
                99);

        assertThat(response.requestedLimit()).isEqualTo(10);
    }

    private static LibrarySeatStatusResponse statusWithSeatItems(List<LibrarySeatItem> seats) {
        long available = seats.stream()
                .filter(seat -> "available".equals(seat.status()))
                .count();
        return new LibrarySeatStatusResponse(
                2,
                "2F",
                seats.size(),
                (int) available,
                seats.size() - (int) available,
                0,
                Instant.parse("2026-06-06T10:00:00Z"),
                List.of(new LibrarySeatZone("2F Zone", seats.size(), (int) available, List.of(), seats)));
    }

    private static LibrarySeatStatusResponse statusWithSeatIds(List<String> seatIds) {
        return new LibrarySeatStatusResponse(
                2,
                "2F",
                10,
                seatIds.size(),
                10 - seatIds.size(),
                0,
                Instant.parse("2026-06-06T10:00:00Z"),
                List.of(new LibrarySeatZone("2F Zone", 10, seatIds.size(), seatIds, List.of())));
    }

    private static LibrarySeatStatusResponse statusWithoutSeatIds() {
        return new LibrarySeatStatusResponse(
                2,
                "2F",
                10,
                3,
                7,
                0,
                Instant.parse("2026-06-06T10:00:00Z"),
                List.of(new LibrarySeatZone("2F Zone", 10, 3, List.of(), List.of())));
    }
}
