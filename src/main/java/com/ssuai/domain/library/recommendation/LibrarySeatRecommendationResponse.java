package com.ssuai.domain.library.recommendation;

import java.util.List;

public record LibrarySeatRecommendationResponse(
        int floor,
        String floorLabel,
        int requestedLimit,
        int liveAvailableSeats,
        int liveSeatItemsSeen,
        int catalogSeatsOnFloor,
        int catalogMatchedAvailableSeats,
        String availabilitySource,
        String message,
        List<String> excludedRooms,
        List<String> warnings,
        List<LibrarySeatRecommendation> recommendations
) {

    public LibrarySeatRecommendationResponse {
        excludedRooms = excludedRooms == null ? List.of() : List.copyOf(excludedRooms);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
