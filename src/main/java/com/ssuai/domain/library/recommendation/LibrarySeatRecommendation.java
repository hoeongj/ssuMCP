package com.ssuai.domain.library.recommendation;

import java.util.List;

public record LibrarySeatRecommendation(
        String seatId,
        String externalSeatId,
        String label,
        String roomCode,
        String roomName,
        String zone,
        String seatType,
        String audience,
        String status,
        int score,
        List<String> matchedPreferences,
        List<String> missingPreferences,
        LibrarySeatAttributes attributes,
        String note
) {

    public LibrarySeatRecommendation {
        matchedPreferences = matchedPreferences == null ? List.of() : List.copyOf(matchedPreferences);
        missingPreferences = missingPreferences == null ? List.of() : List.copyOf(missingPreferences);
    }
}
