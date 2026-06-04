package com.ssuai.domain.meal.dto;

public record MealClosure(
        String restaurant,
        String reason,
        MealClosureType type
) {

    /** Backwards-compatible convenience constructor — defaults to CLOSED. */
    public MealClosure(String restaurant, String reason) {
        this(restaurant, reason, MealClosureType.CLOSED);
    }
}
