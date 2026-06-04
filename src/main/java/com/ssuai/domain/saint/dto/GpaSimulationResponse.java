package com.ssuai.domain.saint.dto;

public record GpaSimulationResponse(
        double currentGpa,
        double currentGpaCredits,
        double currentGpaSum,
        double plannedCredits,
        Double plannedGradePointAverage,
        Double projectedGpa,
        Double targetGpa,
        Double requiredGradePointAverage,
        Boolean achievable,
        double maxGradePoint,
        double maxAchievableGpa
) {
}
