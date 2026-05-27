package com.ssuai.domain.meal.dto;

import java.time.LocalDate;
import java.util.List;

public record MealResponse(
        LocalDate date,
        List<MealItem> meals,
        List<MealClosure> closures
) {

    public MealResponse(LocalDate date, List<MealItem> meals) {
        this(date, meals, List.of());
    }
}
