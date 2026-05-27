package com.ssuai.domain.saint.dto;

public record GraduationRequirementItem(
        String name,
        String category,
        float required,
        float completed,
        float remaining,
        boolean satisfied
) {
}
