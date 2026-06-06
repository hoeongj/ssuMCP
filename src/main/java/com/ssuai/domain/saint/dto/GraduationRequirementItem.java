package com.ssuai.domain.saint.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GraduationRequirementItem(
        String name,
        String category,
        float required,
        float completed,
        float remaining,
        boolean satisfied
) {

    public GraduationRequirementItem {
        remaining = Math.max(0.0f, required - completed);
    }

    /**
     * Completed minus required. Negative means deficient, positive means
     * over-completed. Use {@code remaining} for a user-facing deficit value.
     */
    @JsonProperty("difference")
    public float difference() {
        return completed - required;
    }

    @JsonProperty("creditBased")
    public boolean creditBased() {
        return required > 0.0f || completed > 0.0f;
    }

    @JsonProperty("requirementType")
    public String requirementType() {
        return creditBased() ? "CREDIT" : "GATE";
    }
}
