package com.ssuai.domain.library.reservation.intent;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsResponse;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsRoomSummary;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;

@Component
public class LibraryReservationSeatSelector {

    private final LibraryAvailableSeatsService availableSeatsService;
    private final LibrarySeatConnector seatConnector;
    private final LibrarySeatCatalogService catalogService;
    private final LibraryReservationPreferenceNormalizer preferenceNormalizer;

    public LibraryReservationSeatSelector(
            LibraryAvailableSeatsService availableSeatsService,
            LibrarySeatConnector seatConnector,
            LibrarySeatCatalogService catalogService,
            LibraryReservationPreferenceNormalizer preferenceNormalizer) {
        this.availableSeatsService = availableSeatsService;
        this.seatConnector = seatConnector;
        this.catalogService = catalogService;
        this.preferenceNormalizer = preferenceNormalizer;
    }

    public Optional<LibraryReservationSeatSelection> findAvailableSeat(LibraryReservationIntent intent) {
        LibraryAllAvailableSeatsResponse availability =
                availableSeatsService.getAllAvailableSeats(intent.getSessionKey());
        Set<Integer> roomIds = preferenceNormalizer.parseRoomIds(intent.getPreferredRoomIds());
        Set<String> attributes = preferenceNormalizer.parseAttributeTags(intent.getSeatAttributes());
        Integer floor = parseFloor(intent.getPreferredFloor());
        Long targetSeatId = intent.getTargetSeatId();

        for (LibraryAllAvailableSeatsRoomSummary room : availability.rooms()) {
            if (!roomIds.isEmpty() && !roomIds.contains(room.roomId())) {
                continue;
            }
            for (Integer availableSeatId : room.availableExternalSeatIds()) {
                long seatId = availableSeatId.longValue();
                if (targetSeatId != null && seatId != targetSeatId) {
                    continue;
                }
                if (matchesCatalogFilters(seatId, room.roomId(), floor, attributes)) {
                    return Optional.of(new LibraryReservationSeatSelection(seatId, room.roomId()));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<LibraryReservationSeatSelection> selectionForTargetSeat(long targetSeatId) {
        return catalogService.findRoomIdByExternalSeatId(Long.toString(targetSeatId))
                .map(roomId -> new LibraryReservationSeatSelection(targetSeatId, roomId));
    }

    public Optional<LibraryReservationSeatSelection> findFreshAvailableSeat(
            LibraryReservationIntent intent,
            LibraryReservationSeatSelection candidate,
            String pyxisAuthToken,
            Set<Long> excludedSeatIds) {
        List<PyxisSeatInfo> seats = seatConnector.fetchRoomSeats(candidate.roomId(), pyxisAuthToken);
        Set<Integer> roomIds = preferenceNormalizer.parseRoomIds(intent.getPreferredRoomIds());
        if (!roomIds.isEmpty() && !roomIds.contains(candidate.roomId())) {
            return Optional.empty();
        }
        Set<String> attributes = preferenceNormalizer.parseAttributeTags(intent.getSeatAttributes());
        Integer floor = parseFloor(intent.getPreferredFloor());
        Long targetSeatId = intent.getTargetSeatId();
        Set<Long> excluded = excludedSeatIds == null ? Set.of() : Set.copyOf(excludedSeatIds);

        if (!excluded.contains(candidate.seatId())
                && isFreshMatch(candidate.seatId(), candidate.roomId(), seats, floor, attributes)) {
            return Optional.of(candidate);
        }
        if (targetSeatId != null) {
            return Optional.empty();
        }
        for (PyxisSeatInfo seat : seats) {
            long seatId = seat.externalSeatId();
            if (excluded.contains(seatId) || seatId == candidate.seatId()) {
                continue;
            }
            if (isFreshMatch(seatId, candidate.roomId(), seats, floor, attributes)) {
                return Optional.of(new LibraryReservationSeatSelection(seatId, candidate.roomId()));
            }
        }
        return Optional.empty();
    }

    private boolean isFreshMatch(
            long externalSeatId,
            int roomId,
            List<PyxisSeatInfo> seats,
            Integer floor,
            Set<String> attributes) {
        return seats.stream()
                .anyMatch(seat -> seat.externalSeatId() == externalSeatId
                        && "available".equals(seat.status())
                        && matchesCatalogFilters(externalSeatId, roomId, floor, attributes));
    }

    private boolean matchesCatalogFilters(long externalSeatId, int roomId, Integer floor, Set<String> attributes) {
        if (floor == null && attributes.isEmpty()) {
            return true;
        }
        Optional<LibrarySeatCatalogEntry> entry =
                catalogService.findByExternalSeatId(Long.toString(externalSeatId), roomId);
        if (entry.isEmpty()) {
            return false;
        }
        if (floor != null && entry.get().floor() != floor) {
            return false;
        }
        return entry.get().attributes().tags().containsAll(attributes);
    }

    private static Integer parseFloor(String floor) {
        if (floor == null || floor.isBlank()) {
            return null;
        }
        return Integer.parseInt(floor);
    }
}
