package com.ssuai.domain.dorm.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;

class MockDormMealConnectorTests {

    private final MockDormMealConnector connector = new MockDormMealConnector();

    @Test
    void fetchThisWeekMealReturnsSevenDaysFromMondayToSunday() {
        WeeklyMealResponse response = connector.fetchThisWeekMeal();

        assertThat(response.days()).hasSize(7);
        assertThat(response.startDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(response.endDate()).isEqualTo(response.startDate().plusDays(6));
        assertThat(response.days())
                .extracting(MealResponse::date)
                .isSorted();
        assertThat(response.days())
                .allSatisfy(day -> {
                    assertThat(day.meals())
                            .extracting(MealItem::restaurant)
                            .containsOnly(MockDormMealConnector.RESTAURANT);
                    assertThat(day.closures())
                            .singleElement()
                            .satisfies(closure -> {
                                assertThat(closure.restaurant()).isEqualTo(MockDormMealConnector.RESTAURANT);
                                assertThat(closure.reason()).isEqualTo("조식 미운영");
                            });
                });
    }
}
