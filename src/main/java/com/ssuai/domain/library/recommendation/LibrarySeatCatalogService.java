package com.ssuai.domain.library.recommendation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.LibraryFloor;

@Service
public class LibrarySeatCatalogService {

    private static final String DEFAULT_CATALOG_PATH = "library/seat-catalog.json";
    private static final Comparator<LibrarySeatCatalogEntry> ENTRY_ORDER = Comparator
            .comparingInt(LibrarySeatCatalogEntry::floor)
            .thenComparing(LibrarySeatCatalogEntry::roomCode)
            .thenComparing(LibrarySeatCatalogEntry::seatId, LibrarySeatCatalogService::compareSeatIds);

    private final List<LibrarySeatCatalogEntry> entries;
    private final Map<Integer, Map<String, LibrarySeatCatalogEntry>> entriesByFloorAndSeatId;
    private final Map<String, LibrarySeatCatalogEntry> entriesByExternalSeatId;

    public LibrarySeatCatalogService() {
        this(new ClassPathResource(DEFAULT_CATALOG_PATH), new ObjectMapper());
    }

    LibrarySeatCatalogService(Resource catalogResource, ObjectMapper objectMapper) {
        this.entries = load(catalogResource, objectMapper);
        this.entriesByFloorAndSeatId = this.entries.stream()
                .collect(Collectors.groupingBy(
                        LibrarySeatCatalogEntry::floor,
                        Collectors.toUnmodifiableMap(
                                entry -> normalizeSeatId(entry.seatId()),
                                entry -> entry,
                                (left, right) -> left)));
        this.entriesByExternalSeatId = this.entries.stream()
                .filter(entry -> entry.externalSeatId() != null && !entry.externalSeatId().isBlank())
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.externalSeatId().trim(),
                        entry -> entry,
                        (left, right) -> left));
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
    public Optional<LibrarySeatCatalogEntry> findByExternalSeatId(String externalSeatId) {
        if (externalSeatId == null || externalSeatId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entriesByExternalSeatId.get(externalSeatId.trim()));
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

    private static String normalizeSeatId(String seatId) {
        return seatId.trim().toUpperCase(Locale.ROOT);
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
