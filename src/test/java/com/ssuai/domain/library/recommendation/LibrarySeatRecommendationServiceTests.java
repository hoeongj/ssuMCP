package com.ssuai.domain.library.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibraryRoomAvailableSeatsResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;

class LibrarySeatRecommendationServiceTests {

    private static final String SESSION_KEY = "library-session-key";
    // Floor F2 rooms per FLOOR_ROOMS constant
    private static final int ROOM_OPEN = 54;
    private static final int ROOM_SQUARE = 53;

    private LibraryAvailableSeatsService availableSeatsService;
    private LibrarySeatRecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        availableSeatsService = mock(LibraryAvailableSeatsService.class);
        recommendationService = new LibrarySeatRecommendationService(
                availableSeatsService, new LibrarySeatCatalogService());
    }

    @Test
    void recommendsOnlyCurrentlyAvailableCatalogedSeats() {
        when(availableSeatsService.getRoomAvailableSeats(ROOM_SQUARE, SESSION_KEY))
                .thenReturn(emptyRoom(ROOM_SQUARE, "숭실스퀘어ON(2F)"));
        when(availableSeatsService.getRoomAvailableSeats(ROOM_OPEN, SESSION_KEY))
                .thenReturn(roomWithSeats(ROOM_OPEN, "오픈열람실(2F)", List.of(
                        new PyxisSeatInfo(1, "1", "일반용", "available", 0, 0),
                        new PyxisSeatInfo(2, "2", "일반용", "available", 0, 0),
                        new PyxisSeatInfo(76, "76", "일반용", "occupied", 0, 120),
                        new PyxisSeatInfo(101, "101", "일반용", "available", 0, 0)
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(null, null, false, null, true, null),
                10);

        assertThat(response.availabilitySource()).isEqualTo("live_per_seat");
        assertThat(response.recommendations())
                .extracting(LibrarySeatRecommendation::seatId)
                .containsExactly("1", "2", "101");
        assertThat(response.recommendations())
                .noneMatch(recommendation -> recommendation.seatId().equals("76"));
        assertThat(response.recommendations().getFirst().matchedPreferences())
                .contains("not_standing", "quiet");
    }

    @Test
    void returnsAvailableSeatsInSortedOrder() {
        when(availableSeatsService.getRoomAvailableSeats(ROOM_SQUARE, SESSION_KEY))
                .thenReturn(emptyRoom(ROOM_SQUARE, "숭실스퀘어ON(2F)"));
        when(availableSeatsService.getRoomAvailableSeats(ROOM_OPEN, SESSION_KEY))
                .thenReturn(roomWithSeats(ROOM_OPEN, "오픈열람실(2F)", List.of(
                        new PyxisSeatInfo(10, "10", "일반용", "available", 0, 0),
                        new PyxisSeatInfo(1, "1", "일반용", "available", 0, 0)
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(null, null, null, null, null, null),
                2);

        assertThat(response.availabilitySource()).isEqualTo("live_per_seat");
        assertThat(response.recommendations())
                .extracting(LibrarySeatRecommendation::seatId)
                .containsExactly("1", "10");
    }

    @Test
    void returnsEmptyRecommendationsWhenAllSeatsOccupied() {
        when(availableSeatsService.getRoomAvailableSeats(ROOM_SQUARE, SESSION_KEY))
                .thenReturn(roomWithSeats(ROOM_SQUARE, "숭실스퀘어ON(2F)", List.of(
                        new PyxisSeatInfo(5, "5", "일반용", "occupied", 0, 60)
                )));
        when(availableSeatsService.getRoomAvailableSeats(ROOM_OPEN, SESSION_KEY))
                .thenReturn(roomWithSeats(ROOM_OPEN, "오픈열람실(2F)", List.of(
                        new PyxisSeatInfo(100, "100", "일반용", "occupied", 0, 60)
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(true, true, null, null, null, null),
                null);

        assertThat(response.requestedLimit()).isEqualTo(5);
        assertThat(response.availabilitySource()).isEqualTo("live_per_seat");
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.message()).contains("occupied");
    }

    @Test
    void clampsLimitToTen() {
        when(availableSeatsService.getRoomAvailableSeats(ROOM_SQUARE, SESSION_KEY))
                .thenReturn(emptyRoom(ROOM_SQUARE, "숭실스퀘어ON(2F)"));
        when(availableSeatsService.getRoomAvailableSeats(ROOM_OPEN, SESSION_KEY))
                .thenReturn(roomWithSeats(ROOM_OPEN, "오픈열람실(2F)", List.of(
                        new PyxisSeatInfo(1, "1", "일반용", "available", 0, 0)
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F2,
                SESSION_KEY,
                new LibrarySeatPreference(null, null, null, null, null, null),
                99);

        assertThat(response.requestedLimit()).isEqualTo(10);
    }

    @Test
    void excludesGraduateOnlySeatsByDefault() {
        // Floor 6: label "1" resolves to 대학원열람실(graduate_only), label "91" to 마루열람실
        when(availableSeatsService.getRoomAvailableSeats(57, SESSION_KEY))
                .thenReturn(emptyRoom(57, "마루열람실(6F)"));
        when(availableSeatsService.getRoomAvailableSeats(58, SESSION_KEY))
                .thenReturn(roomWithSeats(58, "대학원열람실(6F)", List.of(
                        new PyxisSeatInfo(1, "1", "일반용", "available", 0, 0),
                        new PyxisSeatInfo(91, "91", "일반용", "available", 0, 0)
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F6, SESSION_KEY, null, 10);

        assertThat(response.recommendations())
                .extracting(LibrarySeatRecommendation::audience)
                .doesNotContain(LibrarySeatRecommendationService.GRADUATE_ONLY_AUDIENCE);
        assertThat(response.excludedRooms()).containsExactly("대학원열람실(6F)");
        assertThat(response.warnings()).isEmpty();
        assertThat(response.message()).contains("대학원");
    }

    @Test
    void includesGraduateOnlySeatsWithWarningWhenExplicitlyRequested() {
        when(availableSeatsService.getRoomAvailableSeats(57, SESSION_KEY))
                .thenReturn(emptyRoom(57, "마루열람실(6F)"));
        when(availableSeatsService.getRoomAvailableSeats(58, SESSION_KEY))
                .thenReturn(roomWithSeats(58, "대학원열람실(6F)", List.of(
                        new PyxisSeatInfo(1, "1", "일반용", "available", 0, 0)
                )));

        LibrarySeatRecommendationResponse response = recommendationService.recommend(
                LibraryFloor.F6, SESSION_KEY, null, 10, true);

        assertThat(response.recommendations())
                .extracting(LibrarySeatRecommendation::audience)
                .contains(LibrarySeatRecommendationService.GRADUATE_ONLY_AUDIENCE);
        assertThat(response.excludedRooms()).isEmpty();
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("대학원"));
    }

    private static LibraryRoomAvailableSeatsResponse emptyRoom(int roomId, String name) {
        return new LibraryRoomAvailableSeatsResponse(
                roomId, name, 0, 0, 0, 0, 0, Instant.parse("2026-06-07T10:00:00Z"), List.of());
    }

    private static LibraryRoomAvailableSeatsResponse roomWithSeats(
            int roomId, String name, List<PyxisSeatInfo> seats) {
        int available = (int) seats.stream().filter(s -> "available".equals(s.status())).count();
        int occupied = (int) seats.stream().filter(s -> "occupied".equals(s.status())).count();
        int away = (int) seats.stream().filter(s -> "away".equals(s.status())).count();
        int inactive = (int) seats.stream().filter(s -> "inactive".equals(s.status())).count();
        return new LibraryRoomAvailableSeatsResponse(
                roomId, name, seats.size(), available, occupied, away, inactive,
                Instant.parse("2026-06-07T10:00:00Z"), seats);
    }
}
