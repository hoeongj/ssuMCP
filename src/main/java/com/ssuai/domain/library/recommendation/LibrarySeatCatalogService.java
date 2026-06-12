package com.ssuai.domain.library.recommendation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.LibraryFloor;

@Service
public class LibrarySeatCatalogService {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatCatalogService.class);

    private static final String DEFAULT_CATALOG_PATH = "library/seat-catalog.json";
    private static final String DEFAULT_ROOM_CATALOG_PATH = "library/seat-room-catalog.json";
    private static final Comparator<LibrarySeatCatalogEntry> ENTRY_ORDER = Comparator
            .comparingInt(LibrarySeatCatalogEntry::floor)
            .thenComparing(LibrarySeatCatalogEntry::roomCode)
            .thenComparing(LibrarySeatCatalogEntry::seatId, LibrarySeatCatalogService::compareSeatIds);
    private static final Map<String, String> ROOM_CODE_ALIASES = Map.of(
            "multi-lounge-5f", "pc-multi-zone-5f");

    private final List<LibrarySeatCatalogEntry> entries;
    private final Map<Integer, Map<String, LibrarySeatCatalogEntry>> entriesByFloorAndSeatId;
    private final Map<Integer, Map<String, LibrarySeatCatalogEntry>> entriesByRoomAndExternalSeatId;

    public LibrarySeatCatalogService() {
        this(
                new ClassPathResource(DEFAULT_CATALOG_PATH),
                new ClassPathResource(DEFAULT_ROOM_CATALOG_PATH),
                new ObjectMapper());
    }

    LibrarySeatCatalogService(Resource catalogResource, ObjectMapper objectMapper) {
        this(catalogResource, new ClassPathResource(DEFAULT_ROOM_CATALOG_PATH), objectMapper);
    }

    LibrarySeatCatalogService(Resource catalogResource, Resource roomCatalogResource, ObjectMapper objectMapper) {
        this.entries = load(catalogResource, objectMapper);
        this.entriesByFloorAndSeatId = this.entries.stream()
                .collect(Collectors.groupingBy(
                        LibrarySeatCatalogEntry::floor,
                        Collectors.toUnmodifiableMap(
                                entry -> normalizeSeatId(entry.seatId()),
                                entry -> entry,
                                (left, right) -> left)));
        Map<String, Integer> roomIdsByCode = loadRoomIdsByCode(roomCatalogResource, objectMapper);
        this.entriesByRoomAndExternalSeatId = buildEntriesByRoomAndExternalSeatId(this.entries, roomIdsByCode);
    }

    public List<LibrarySeatCatalogEntry> entriesFor(LibraryFloor floor) {
        return entries.stream()
                .filter(entry -> entry.belongsTo(floor))
                .sorted(ENTRY_ORDER)
                .toList();
    }

    public Optional<LibrarySeatCatalogEntry> find(LibraryFloor floor, String seatId) {
        if (floor == null || seatId == null || seatId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entriesByFloorAndSeatId
                .getOrDefault(floor.code(), Map.of())
                .get(normalizeSeatId(seatId)));
    }

    /**
     * Looks up a catalog entry by the Pyxis-internal seat id (externalSeatId).
     * The visible seat number users know is {@code label}/{@code seatId}, not this id,
     * so user-facing messages must resolve through this lookup instead of echoing the raw id.
     */
    public Optional<LibrarySeatCatalogEntry> findByExternalSeatId(String externalSeatId, int roomId) {
        String normalizedExternalSeatId = normalizeExternalSeatId(externalSeatId);
        if (normalizedExternalSeatId == null) {
            return Optional.empty();
        }
        Map<String, LibrarySeatCatalogEntry> roomMap = entriesByRoomAndExternalSeatId.get(roomId);
        if (roomMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(roomMap.get(normalizedExternalSeatId));
    }

    public Optional<LibrarySeatCatalogEntry> findByExternalSeatId(String externalSeatId) {
        String normalizedExternalSeatId = normalizeExternalSeatId(externalSeatId);
        if (normalizedExternalSeatId == null) {
            return Optional.empty();
        }
        List<LibrarySeatCatalogEntry> matches = entriesByRoomAndExternalSeatId.values().stream()
                .map(roomEntries -> roomEntries.get(normalizedExternalSeatId))
                .filter(Objects::nonNull)
                .toList();
        if (matches.size() > 1) {
            log.warn("Ambiguous externalSeatId {} found in {} rooms; returning first match",
                    normalizedExternalSeatId,
                    matches.size());
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }

    private static List<LibrarySeatCatalogEntry> load(Resource catalogResource, ObjectMapper objectMapper) {
        try (InputStream input = catalogResource.getInputStream()) {
            List<LibrarySeatCatalogEntry> loaded =
                    objectMapper.readValue(input, new TypeReference<List<LibrarySeatCatalogEntry>>() {});
            return loaded.stream()
                    .sorted(ENTRY_ORDER)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load library seat catalog", exception);
        }
    }

    private static Map<String, Integer> loadRoomIdsByCode(Resource roomCatalogResource, ObjectMapper objectMapper) {
        try (InputStream input = roomCatalogResource.getInputStream()) {
            List<LibrarySeatRoomCatalogEntry> loaded =
                    objectMapper.readValue(input, new TypeReference<List<LibrarySeatRoomCatalogEntry>>() {});
            Map<String, Integer> roomIdsByCode = new LinkedHashMap<>();
            for (LibrarySeatRoomCatalogEntry room : loaded) {
                if (room.roomId() != null) {
                    roomIdsByCode.putIfAbsent(normalizeRoomCode(room.roomCode()), room.roomId());
                }
            }
            ROOM_CODE_ALIASES.forEach((alias, targetRoomCode) -> {
                Integer roomId = roomIdsByCode.get(targetRoomCode);
                if (roomId != null) {
                    roomIdsByCode.putIfAbsent(alias, roomId);
                }
            });
            return Collections.unmodifiableMap(roomIdsByCode);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load library seat room catalog", exception);
        }
    }

    private static Map<Integer, Map<String, LibrarySeatCatalogEntry>> buildEntriesByRoomAndExternalSeatId(
            List<LibrarySeatCatalogEntry> entries,
            Map<String, Integer> roomIdsByCode) {
        Map<Integer, Map<String, LibrarySeatCatalogEntry>> entriesByRoom = new LinkedHashMap<>();
        Set<String> missingRoomCodes = new LinkedHashSet<>();

        for (LibrarySeatCatalogEntry entry : entries) {
            String externalSeatId = normalizeExternalSeatId(entry.externalSeatId());
            if (externalSeatId == null) {
                continue;
            }
            Integer roomId = roomIdsByCode.get(normalizeRoomCode(entry.roomCode()));
            if (roomId == null) {
                missingRoomCodes.add(entry.roomCode());
                continue;
            }
            entriesByRoom
                    .computeIfAbsent(roomId, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(externalSeatId, entry);
        }

        if (!missingRoomCodes.isEmpty()) {
            log.warn("No roomId for library seat catalog roomCode(s) {}; externalSeatId room-scoped lookup skipped",
                    missingRoomCodes);
        }

        Map<Integer, Map<String, LibrarySeatCatalogEntry>> immutableEntriesByRoom = new LinkedHashMap<>();
        entriesByRoom.forEach((roomId, roomEntries) ->
                immutableEntriesByRoom.put(
                        roomId,
                        Collections.unmodifiableMap(new LinkedHashMap<>(roomEntries))));
        return Collections.unmodifiableMap(immutableEntriesByRoom);
    }

    private static String normalizeSeatId(String seatId) {
        return seatId.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeExternalSeatId(String externalSeatId) {
        return externalSeatId == null || externalSeatId.isBlank() ? null : externalSeatId.trim();
    }

    private static String normalizeRoomCode(String roomCode) {
        return roomCode == null ? "" : roomCode.trim().toLowerCase(Locale.ROOT);
    }

    static int compareSeatIds(String left, String right) {
        return SeatSortKey.from(left).compareTo(SeatSortKey.from(right));
    }

    private record SeatSortKey(String prefix, int number, String raw) implements Comparable<SeatSortKey> {

        private static SeatSortKey from(String value) {
            String raw = normalizeSeatId(value);
            int digitStart = 0;
            while (digitStart < raw.length() && !Character.isDigit(raw.charAt(digitStart))) {
                digitStart++;
            }
            String prefix = raw.substring(0, digitStart);
            String digits = raw.substring(digitStart);
            int number = !digits.isEmpty() && digits.chars().allMatch(Character::isDigit)
                    ? Integer.parseInt(digits)
                    : Integer.MAX_VALUE;
            return new SeatSortKey(prefix, number, raw);
        }

        @Override
        public int compareTo(SeatSortKey other) {
            int prefixOrder = prefix.compareTo(other.prefix);
            if (prefixOrder != 0) {
                return prefixOrder;
            }
            int numberOrder = Integer.compare(number, other.number);
            if (numberOrder != 0) {
                return numberOrder;
            }
            return raw.compareTo(other.raw);
        }
    }
}
