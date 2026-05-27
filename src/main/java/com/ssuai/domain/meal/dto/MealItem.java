package com.ssuai.domain.meal.dto;

import java.util.List;

public record MealItem(
        String restaurant,
        MealType type,
        String corner,
        List<String> menu
) {
}
