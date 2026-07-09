package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsResponse;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsRoomSummary;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;

class LibraryReservationSeatSelectorTests {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");
    private static final String SESSION_KEY = "session";
    private static final String TOKEN = "pyxis-token";
    private static final int ROOM_ID = 57;
    private static final long STALE_SEAT_ID = 3179L;
    private static final long REPLACEMENT_SEAT_ID = 3180L;

    private final LibraryAvailableSeatsService availableSeatsService = mock(LibraryAvailableSeatsService.class);
    private final LibrarySeatConnector seatConnector = mock(LibrarySeatConnector.class);
    private final LibrarySeatCatalogService catalogService = mock(LibrarySeatCatalogService.class);
    private final LibraryReservationSeatSelector selector = new LibraryReservationSeatSelector(
            availableSeatsService,
            seatConnector,
            catalogService,
            new LibraryReservationPreferenceNormalizer());

    @Test
    void cachedSelectionCarriesRoomId() {
        when(availableSeatsService.getAllAvailableSeats(SESSION_KEY))
                .thenReturn(new LibraryAllAvailableSeatsResponse(
                        1,
                        0,
                        NOW,
                        List.of(new LibraryAllAvailableSeatsRoomSummary(
                                ROOM_ID,
                                "room",
                                2,
                                1,
                                0,
                                List.of((int) STALE_SEAT_ID),
                                List.of("74")))));

        Optional<LibraryReservationSeatSelection> selection = selector.findAvailableSeat(broadIntent());

        assertThat(selection).contains(new LibraryReservationSeatSelection(STALE_SEAT_ID, ROOM_ID));
    }

    @Test
    void freshReadReselectsFromRoomConnectorWhenCandidateGone() {
        when(seatConnector.fetchRoomSeats(ROOM_ID, TOKEN))
                .thenReturn(List.of(
                        seat(STALE_SEAT_ID, "occupied"),
                        seat(REPLACEMENT_SEAT_ID, "available")));

        Optional<LibraryReservationSeatSelection> selection = selector.findFreshAvailableSeat(
                broadIntent(),
                new LibraryReservationSeatSelection(STALE_SEAT_ID, ROOM_ID),
                TOKEN,
                Set.of());

        assertThat(selection).contains(new LibraryReservationSeatSelection(REPLACEMENT_SEAT_ID, ROOM_ID));
        verify(seatConnector).fetchRoomSeats(ROOM_ID, TOKEN);
        verifyNoInteractions(availableSeatsService);
    }

    @Test
    void targetSeatDoesNotRetargetToDifferentFreshSeat() {
        when(seatConnector.fetchRoomSeats(ROOM_ID, TOKEN))
                .thenReturn(List.of(
                        seat(STALE_SEAT_ID, "occupied"),
                        seat(REPLACEMENT_SEAT_ID, "available")));

        Optional<LibraryReservationSeatSelection> selection = selector.findFreshAvailableSeat(
                targetIntent(),
                new LibraryReservationSeatSelection(STALE_SEAT_ID, ROOM_ID),
                TOKEN,
                Set.of());

        assertThat(selection).isEmpty();
    }

    @Test
    void targetSeatSelectionUsesCatalogRoomLookup() {
        when(catalogService.findRoomIdByExternalSeatId(Long.toString(STALE_SEAT_ID)))
                .thenReturn(Optional.of(ROOM_ID));

        Optional<LibraryReservationSeatSelection> selection = selector.selectionForTargetSeat(STALE_SEAT_ID);

        assertThat(selection).contains(new LibraryReservationSeatSelection(STALE_SEAT_ID, ROOM_ID));
    }

    private static PyxisSeatInfo seat(long externalSeatId, String status) {
        return new PyxisSeatInfo((int) externalSeatId, Long.toString(externalSeatId), "general", status, 0, 0);
    }

    private static LibraryReservationIntent broadIntent() {
        return LibraryReservationIntent.requested(
                SESSION_KEY,
                SESSION_KEY,
                null,
                null,
                null,
                null,
                NOW,
                NOW.plus(Duration.ofHours(2)));
    }

    private static LibraryReservationIntent targetIntent() {
        return LibraryReservationIntent.requested(
                SESSION_KEY,
                SESSION_KEY,
                null,
                null,
                null,
                STALE_SEAT_ID,
                NOW,
                NOW.plus(Duration.ofHours(2)));
    }
}
