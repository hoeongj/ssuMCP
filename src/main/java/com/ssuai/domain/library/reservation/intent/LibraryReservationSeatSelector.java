package com.ssuai.domain.library.reservation.intent;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsResponse;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsRoomSummary;
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;

@Component
public class LibraryReservationSeatSelector {

    private final LibraryAvailableSeatsService availableSeatsService;
    private final LibrarySeatCatalogService catalogService;
    private final LibraryReservationPreferenceNormalizer preferenceNormalizer;

    public LibraryReservationSeatSelector(
            LibraryAvailableSeatsService availableSeatsService,
            LibrarySeatCatalogService catalogService,
            LibraryReservationPreferenceNormalizer preferenceNormalizer) {
        this.availableSeatsService = availableSeatsService;
        this.catalogService = catalogService;
        this.preferenceNormalizer = preferenceNormalizer;
    }

    public Optional<Long> findAvailableSeat(LibraryReservationIntent intent) {
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
                if (matchesCatalogFilters(seatId, floor, attributes)) {
                    return Optional.of(seatId);
                }
            }
        }
        return Optional.empty();
    }

    private boolean matchesCatalogFilters(long externalSeatId, Integer floor, Set<String> attributes) {
        if (floor == null && attributes.isEmpty()) {
            return true;
        }
        Optional<LibrarySeatCatalogEntry> entry =
                catalogService.findByExternalSeatId(Long.toString(externalSeatId));
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
