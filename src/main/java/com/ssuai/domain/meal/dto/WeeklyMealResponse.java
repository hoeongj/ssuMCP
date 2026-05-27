package com.ssuai.domain.meal.dto;

import java.time.LocalDate;
import java.util.List;

public record WeeklyMealResponse(
        LocalDate startDate,
        LocalDate endDate,
        List<MealResponse> days
) {
}
