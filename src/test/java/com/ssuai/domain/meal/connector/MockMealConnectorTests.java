package com.ssuai.domain.meal.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;

class MockMealConnectorTests {

    private final MockMealConnector mealConnector = new MockMealConnector();

    @Test
    void fetchMealReturnsMockMealsForRequestedRestaurant() {
        LocalDate date = LocalDate.of(2026, 5, 6);

        MealResponse response = mealConnector.fetchMeal(date, MealRestaurant.STUDENT);

        assertThat(response.date()).isEqualTo(date);
        assertThat(response.meals())
                .hasSize(3)
                .extracting(MealItem::type)
                .containsExactly(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER);
        assertThat(response.meals())
                .extracting(MealItem::restaurant)
                .containsOnly("학생식당");
        assertThat(response.meals())
                .extracting(MealItem::corner)
                .containsExactly("조식", "중식", "석식");
        assertThat(response.meals())
                .allSatisfy(meal -> assertThat(meal.menu()).isNotEmpty());
    }

    @Test
    void fetchMealStampsRequestedRestaurantNameOnEveryItem() {
        MealResponse response = mealConnector.fetchMeal(LocalDate.of(2026, 5, 6), MealRestaurant.DODAM);

        assertThat(response.meals())
                .extracting(MealItem::restaurant)
                .containsOnly("숭실도담식당");
    }
}
