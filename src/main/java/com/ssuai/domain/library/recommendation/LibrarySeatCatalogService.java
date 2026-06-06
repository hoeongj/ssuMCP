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

    private final List<LibrarySeatCatalogEntry> entries;
    private final Map<Integer, Map<String, LibrarySeatCatalogEntry>> entriesByFloorAndSeatId;

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
    }

    public List<LibrarySeatCatalogEntry> entriesFor(LibraryFloor floor) {
        return entries.stream()
                .filter(entry -> entry.belongsTo(floor))
                .sorted(Comparator.comparing(LibrarySeatCatalogEntry::seatId))
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

    private static List<LibrarySeatCatalogEntry> load(Resource catalogResource, ObjectMapper objectMapper) {
        try (InputStream input = catalogResource.getInputStream()) {
            List<LibrarySeatCatalogEntry> loaded =
                    objectMapper.readValue(input, new TypeReference<List<LibrarySeatCatalogEntry>>() {});
            return loaded.stream()
                    .sorted(Comparator
                            .comparingInt(LibrarySeatCatalogEntry::floor)
                            .thenComparing(LibrarySeatCatalogEntry::seatId))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load library seat catalog", exception);
        }
    }

    private static String normalizeSeatId(String seatId) {
        return seatId.trim().toUpperCase(Locale.ROOT);
    }
}
