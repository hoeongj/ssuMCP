package com.ssuai.domain.library.recommendation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class LibrarySeatRoomCatalogService {

    private static final String DEFAULT_ROOM_CATALOG_PATH = "library/seat-room-catalog.json";

    private final List<LibrarySeatRoomCatalogEntry> rooms;

    public LibrarySeatRoomCatalogService() {
        this(new ClassPathResource(DEFAULT_ROOM_CATALOG_PATH), new ObjectMapper());
    }

    LibrarySeatRoomCatalogService(Resource roomCatalogResource, ObjectMapper objectMapper) {
        this.rooms = load(roomCatalogResource, objectMapper);
    }

    public LibrarySeatRoomCatalogResponse catalog(String floorCode, String roomCode, Boolean includeLayout) {
        return catalog(floorCode, roomCode, includeLayout, null);
    }

    public LibrarySeatRoomCatalogResponse catalog(
            String floorCode, String roomCode, Boolean includeLayout, Boolean debug) {
        boolean withLayout = Boolean.TRUE.equals(includeLayout);
        boolean withCaptureNotes = Boolean.TRUE.equals(debug);
        List<LibrarySeatRoomCatalogEntry> filtered = rooms.stream()
                .filter(room -> matchesFloor(room, floorCode))
                .filter(room -> matchesRoom(room, roomCode))
                .map(room -> withLayout ? room : room.withoutLayout())
                .map(room -> withCaptureNotes ? room : room.withoutCaptureNotes())
                .toList();

        return new LibrarySeatRoomCatalogResponse(
                filtered.size(),
                withLayout,
                "Static room catalog built from the user's seat-map screenshots. "
                        + "Use live availability before recommending or reserving a seat.",
                filtered);
    }

    public List<LibrarySeatRoomCatalogEntry> rooms() {
        return rooms;
    }

    private static List<LibrarySeatRoomCatalogEntry> load(Resource roomCatalogResource, ObjectMapper objectMapper) {
        try (InputStream input = roomCatalogResource.getInputStream()) {
            List<LibrarySeatRoomCatalogEntry> loaded =
                    objectMapper.readValue(input, new TypeReference<List<LibrarySeatRoomCatalogEntry>>() {});
            return loaded.stream()
                    .sorted(Comparator
                            .comparing(LibrarySeatRoomCatalogEntry::floorCode)
                            .thenComparing(LibrarySeatRoomCatalogEntry::roomCode))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load library seat room catalog", exception);
        }
    }

    private static boolean matchesFloor(LibrarySeatRoomCatalogEntry room, String floorCode) {
        if (floorCode == null || floorCode.isBlank()) {
            return true;
        }
        return normalizeFloorCode(room.floorCode()).equals(normalizeFloorCode(floorCode));
    }

    private static boolean matchesRoom(LibrarySeatRoomCatalogEntry room, String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return true;
        }
        return room.roomCode().equalsIgnoreCase(roomCode.trim());
    }

    private static String normalizeFloorCode(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("2") || normalized.equals("5") || normalized.equals("6")) {
            return normalized + "F";
        }
        return normalized;
    }
}
