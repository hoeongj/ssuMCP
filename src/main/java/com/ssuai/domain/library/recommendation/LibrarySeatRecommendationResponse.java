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
        List<LibrarySeatRecommendation> recommendations
) {

    public LibrarySeatRecommendationResponse {
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }
}
