package com.ssuai.domain.library.recommendation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibraryRoomAvailableSeatsResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;

@Service
public class LibrarySeatRecommendationService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int BASE_AVAILABLE_SCORE = 100;
    public static final String GRADUATE_ONLY_AUDIENCE = "graduate_only";

    private static final Map<LibraryFloor, List<Integer>> FLOOR_ROOMS;

    static {
        Map<LibraryFloor, List<Integer>> m = new LinkedHashMap<>();
        m.put(LibraryFloor.F2, List.of(53, 54));
        m.put(LibraryFloor.F5, List.of(59, 60));
        m.put(LibraryFloor.F6, List.of(57, 58));
        FLOOR_ROOMS = Collections.unmodifiableMap(m);
    }

    private final LibraryAvailableSeatsService availableSeatsService;
    private final LibrarySeatCatalogService catalogService;

    public LibrarySeatRecommendationService(
            LibraryAvailableSeatsService availableSeatsService,
            LibrarySeatCatalogService catalogService) {
        this.availableSeatsService = availableSeatsService;
        this.catalogService = catalogService;
    }

    public LibrarySeatRecommendationResponse recommend(
            LibraryFloor floor,
            String sessionKey,
            LibrarySeatPreference preference,
            Integer requestedLimit
    ) {
        return recommend(floor, sessionKey, preference, requestedLimit, null);
    }

    public LibrarySeatRecommendationResponse recommend(
            LibraryFloor floor,
            String sessionKey,
            LibrarySeatPreference preference,
            Integer requestedLimit,
            Boolean includeGraduateOnly
    ) {
        int limit = normalizeLimit(requestedLimit);
        boolean withGraduateOnly = Boolean.TRUE.equals(includeGraduateOnly);
        LibrarySeatPreference effectivePreference = preference == null
                ? new LibrarySeatPreference(null, null, null, null, null, null)
                : preference;

        List<Integer> roomIds = FLOOR_ROOMS.getOrDefault(floor, List.of());
        Map<String, String> statusByLabel = new LinkedHashMap<>();
        int liveSeatItemsSeen = 0;

        for (int roomId : roomIds) {
            LibraryRoomAvailableSeatsResponse roomData =
                    availableSeatsService.getRoomAvailableSeats(roomId, sessionKey);
            for (PyxisSeatInfo seat : roomData.seats()) {
                statusByLabel.put(seat.label(), seat.status());
                liveSeatItemsSeen++;
            }
        }

        int liveAvailable = (int) statusByLabel.values().stream()
                .filter("available"::equals)
                .count();

        Set<String> excludedRooms = new LinkedHashSet<>();
        List<LibrarySeatRecommendation> allRecommendations = statusByLabel.entrySet().stream()
                .filter(e -> "available".equals(e.getValue()))
                .flatMap(e -> catalogService.find(floor, e.getKey()).stream()
                        .filter(entry -> {
                            if (!withGraduateOnly && GRADUATE_ONLY_AUDIENCE.equals(entry.audience())) {
                                excludedRooms.add(entry.roomName());
                                return false;
                            }
                            return true;
                        })
                        .map(entry -> buildRecommendation(entry, e.getValue(), effectivePreference)))
                .sorted(Comparator
                        .comparingInt(LibrarySeatRecommendation::score).reversed()
                        .thenComparing(LibrarySeatRecommendation::seatId, LibrarySeatCatalogService::compareSeatIds))
                .toList();

        List<LibrarySeatRecommendation> limited = allRecommendations.stream()
                .limit(limit)
                .toList();

        List<String> warnings = new ArrayList<>();
        if (withGraduateOnly && limited.stream()
                .anyMatch(item -> GRADUATE_ONLY_AUDIENCE.equals(item.audience()))) {
            warnings.add("대학원열람실은 대학원생 전용일 수 있습니다. 이용 자격을 확인하세요.");
        }

        String source = liveSeatItemsSeen > 0 ? "live_per_seat" : "no_seats_found";
        return new LibrarySeatRecommendationResponse(
                floor.code(),
                floor.displayLabel(),
                limit,
                source,
                messageFor(liveSeatItemsSeen, liveAvailable, allRecommendations, effectivePreference, excludedRooms),
                List.copyOf(excludedRooms),
                warnings,
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
            int liveSeatItemsSeen,
            int liveAvailable,
            List<LibrarySeatRecommendation> recommendations,
            LibrarySeatPreference preference,
            Set<String> excludedRooms) {
        String exclusionSuffix = excludedRooms.isEmpty()
                ? ""
                : " 대학원 전용 열람실(" + String.join(", ", excludedRooms)
                        + ")은 기본 제외했습니다. 포함하려면 include_graduate_only=true.";
        if (liveSeatItemsSeen == 0) {
            return "No per-seat data was returned for the requested floor. The library may be closed.";
        }
        if (liveAvailable == 0) {
            return "All seats on this floor are currently occupied.";
        }
        if (recommendations.isEmpty()) {
            if (!excludedRooms.isEmpty()) {
                return "사용 가능한 좌석이 대학원 전용 열람실에만 있습니다." + exclusionSuffix;
            }
            return "No available live seats matched the static catalog. "
                    + "Add the floor's seat IDs to library/seat-catalog.json.";
        }
        if (!preference.hasAnyPreference()) {
            return "No preferences were provided, so available catalog seats are sorted deterministically."
                    + exclusionSuffix;
        }
        return "Recommendations are ranked by live availability and the requested seat preferences."
                + exclusionSuffix;
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
}
