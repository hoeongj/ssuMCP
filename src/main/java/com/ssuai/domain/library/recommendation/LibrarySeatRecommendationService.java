package com.ssuai.domain.library.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatItem;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.service.LibrarySeatService;

@Service
public class LibrarySeatRecommendationService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int BASE_AVAILABLE_SCORE = 100;

    private final LibrarySeatService seatService;
    private final LibrarySeatCatalogService catalogService;

    public LibrarySeatRecommendationService(
            LibrarySeatService seatService,
            LibrarySeatCatalogService catalogService) {
        this.seatService = seatService;
        this.catalogService = catalogService;
    }

    public LibrarySeatRecommendationResponse recommend(
            LibraryFloor floor,
            String sessionKey,
            LibrarySeatPreference preference,
            Integer requestedLimit
    ) {
        int limit = normalizeLimit(requestedLimit);
        LibrarySeatPreference effectivePreference = preference == null
                ? new LibrarySeatPreference(null, null, null, null, null, null)
                : preference;

        LibrarySeatStatusResponse status = seatService.getSeatStatusForSession(floor, sessionKey);
        AvailableSeatSnapshot availableSeats = AvailableSeatSnapshot.from(status);
        int catalogSeatsOnFloor = catalogService.entriesFor(floor).size();

        List<LibrarySeatRecommendation> allRecommendations = availableSeats.seatIds().stream()
                .flatMap(seatId -> catalogService.find(floor, seatId).stream()
                        .map(entry -> buildRecommendation(entry, availableSeats.statusOf(seatId), effectivePreference)))
                .sorted(Comparator
                        .comparingInt(LibrarySeatRecommendation::score).reversed()
                        .thenComparing(LibrarySeatRecommendation::seatId))
                .toList();

        List<LibrarySeatRecommendation> limited = allRecommendations.stream()
                .limit(limit)
                .toList();

        return new LibrarySeatRecommendationResponse(
                status.floor(),
                status.floorLabel(),
                limit,
                availableSeats.seatIds().size(),
                availableSeats.liveSeatItemsSeen(),
                catalogSeatsOnFloor,
                allRecommendations.size(),
                availableSeats.source(),
                messageFor(availableSeats, allRecommendations, effectivePreference),
                limited);
    }

    private static LibrarySeatRecommendation buildRecommendation(
            LibrarySeatCatalogEntry entry,
            String status,
            LibrarySeatPreference preference) {
        Score score = score(entry.attributes(), preference);
        return new LibrarySeatRecommendation(
                entry.seatId(),
                entry.externalSeatId(),
                entry.label(),
                entry.roomCode(),
                entry.roomName(),
                entry.zone(),
                entry.seatType(),
                entry.audience(),
                status,
                score.value(),
                score.matchedPreferences(),
                score.missingPreferences(),
                entry.attributes(),
                entry.note());
    }

    private static Score score(LibrarySeatAttributes attributes, LibrarySeatPreference preference) {
        int value = BASE_AVAILABLE_SCORE;
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        ScoringResult window = scoreBoolean("window", preference.window(), attributes.window());
        value += window.delta();
        matched.addAll(window.matched());
        missing.addAll(window.missing());

        ScoringResult outlet = scoreBoolean("outlet", preference.outlet(), attributes.outlet());
        value += outlet.delta();
        matched.addAll(outlet.matched());
        missing.addAll(outlet.missing());

        ScoringResult standing = scoreBoolean("standing", preference.standing(), attributes.standing());
        value += standing.delta();
        matched.addAll(standing.matched());
        missing.addAll(standing.missing());

        ScoringResult edge = scoreBoolean("edge", preference.edge(), attributes.edge());
        value += edge.delta();
        matched.addAll(edge.matched());
        missing.addAll(edge.missing());

        ScoringResult quiet = scoreBoolean("quiet", preference.quiet(), attributes.quiet());
        value += quiet.delta();
        matched.addAll(quiet.matched());
        missing.addAll(quiet.missing());

        ScoringResult nearEntrance =
                scoreBoolean("nearEntrance", preference.nearEntrance(), attributes.nearEntrance());
        value += nearEntrance.delta();
        matched.addAll(nearEntrance.matched());
        missing.addAll(nearEntrance.missing());

        return new Score(value, matched, missing);
    }

    private static ScoringResult scoreBoolean(String key, Boolean preferred, boolean actual) {
        if (preferred == null) {
            return new ScoringResult(0, List.of(), List.of());
        }
        if (preferred == actual) {
            String label = preferred ? key : "not_" + key;
            return new ScoringResult(preferred ? 20 : 10, List.of(label), List.of());
        }
        String label = preferred ? key : "not_" + key;
        return new ScoringResult(preferred ? -15 : -10, List.of(), List.of(label));
    }

    private static String messageFor(
            AvailableSeatSnapshot availableSeats,
            List<LibrarySeatRecommendation> recommendations,
            LibrarySeatPreference preference) {
        if (availableSeats.liveSeatItemsSeen() == 0 && availableSeats.seatIds().isEmpty()) {
            return "Only floor-level availability is available from the live connector. "
                    + "Capture the Pyxis seat-map API to unlock seat-level recommendations.";
        }
        if (recommendations.isEmpty()) {
            return "No currently available live seats matched the static catalog. "
                    + "Add the floor's seat IDs to library/seat-catalog.json or capture the live seat-map IDs.";
        }
        if (!preference.hasAnyPreference()) {
            return "No preferences were provided, so available catalog seats are sorted deterministically.";
        }
        return "Recommendations are ranked by live availability and the requested seat preferences.";
    }

    private static int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    }

    private record Score(
            int value,
            List<String> matchedPreferences,
            List<String> missingPreferences
    ) {
    }

    private record ScoringResult(
            int delta,
            List<String> matched,
            List<String> missing
    ) {
    }

    private record AvailableSeatSnapshot(
            Set<String> seatIds,
            Map<String, String> statusBySeatId,
            int liveSeatItemsSeen,
            String source
    ) {

        static AvailableSeatSnapshot from(LibrarySeatStatusResponse status) {
            Map<String, String> statusBySeatId = new LinkedHashMap<>();
            int liveSeatItemsSeen = 0;
            for (LibrarySeatZone zone : status.zones()) {
                liveSeatItemsSeen += zone.seats().size();
                for (LibrarySeatItem seat : zone.seats()) {
                    statusBySeatId.put(seat.id(), seat.status());
                }
            }

            if (liveSeatItemsSeen > 0) {
                Set<String> liveAvailable = new LinkedHashSet<>();
                statusBySeatId.forEach((seatId, seatStatus) -> {
                    if (isAvailable(seatStatus)) {
                        liveAvailable.add(seatId);
                    }
                });
                return new AvailableSeatSnapshot(
                        liveAvailable, statusBySeatId, liveSeatItemsSeen, "live_seat_items");
            }

            Set<String> zoneAvailableSeatIds = new LinkedHashSet<>();
            for (LibrarySeatZone zone : status.zones()) {
                zoneAvailableSeatIds.addAll(zone.seatIds());
                for (String seatId : zone.seatIds()) {
                    statusBySeatId.put(seatId, "available");
                }
            }
            String source = zoneAvailableSeatIds.isEmpty() ? "floor_only" : "live_available_seat_ids";
            return new AvailableSeatSnapshot(zoneAvailableSeatIds, statusBySeatId, 0, source);
        }

        String statusOf(String seatId) {
            return statusBySeatId.getOrDefault(seatId, "available");
        }

        private static boolean isAvailable(String status) {
            if (status == null) {
                return false;
            }
            String normalized = status.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("available")
                    || normalized.equals("empty")
                    || normalized.equals("free");
        }
    }
}
