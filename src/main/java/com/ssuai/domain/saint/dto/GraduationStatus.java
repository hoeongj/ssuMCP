package com.ssuai.domain.saint.dto;

import java.util.List;

public record GraduationStatus(
        boolean isGraduatable,
        String studentName,
        String department,
        int grade,
        float completedPoints,
        float graduationPoints,
        List<GraduationRequirementItem> requirements
) {

    public GraduationStatus {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
