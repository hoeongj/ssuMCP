package com.ssuai.domain.library.events;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;

@Service
public class LibrarySeatEventSeatResolver {

    private final LibrarySeatCatalogService seatCatalogService;
    private final Map<String, Integer> roomIdsByCode;

    public LibrarySeatEventSeatResolver(
            LibrarySeatCatalogService seatCatalogService,
            LibrarySeatRoomCatalogService roomCatalogService) {
        this.seatCatalogService = seatCatalogService;
        this.roomIdsByCode = roomCatalogService.rooms().stream()
                .filter(room -> room.roomId() != null)
                .collect(Collectors.toUnmodifiableMap(
                        room -> normalize(room.roomCode()),
                        LibrarySeatRoomCatalogEntry::roomId,
                        (left, right) -> left));
    }

    public Optional<Integer> roomIdForExternalSeatId(Long externalSeatId) {
        if (externalSeatId == null) {
            return Optional.empty();
        }
        return seatCatalogService.findByExternalSeatId(Long.toString(externalSeatId))
                .map(entry -> normalize(entry.roomCode()))
                .map(roomIdsByCode::get);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
