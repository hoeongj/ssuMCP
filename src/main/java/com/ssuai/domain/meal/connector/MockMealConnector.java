package com.ssuai.domain.meal.connector;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;

@Component
@ConditionalOnProperty(name = "ssuai.connector.meal", havingValue = "mock", matchIfMissing = true)
public class MockMealConnector implements MealConnector {

    @Override
    public MealResponse fetchMeal(LocalDate date, MealRestaurant restaurant) {
        String name = restaurant.displayName();
        List<MealItem> meals = List.of(
                new MealItem(name, MealType.BREAKFAST, "조식", List.of("흰밥", "미역국", "계란말이", "김치")),
                new MealItem(name, MealType.LUNCH, "중식", List.of("보리밥", "된장찌개", "제육볶음", "콩나물무침", "김치")),
                new MealItem(name, MealType.DINNER, "석식", List.of("흰밥", "김치찌개", "고등어구이", "시금치나물", "김치"))
        );
        return new MealResponse(date, meals);
    }
}
