package com.ssuai.domain.meal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.connector.MealConnector;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.global.exception.ConnectorUnavailableException;

class WeeklyMealCacheTests {

    private final MealConnector mealConnector = mock(MealConnector.class);
    private final WeeklyMealCache cache = new WeeklyMealCache(mealConnector, Runnable::run);

    @Test
    void warmCacheOnStartupFillsAllRestaurantsForAllSevenDays() {
        when(mealConnector.fetchMeal(any(LocalDate.class), any(MealRestaurant.class)))
                .thenAnswer(invocation -> new MealResponse(invocation.getArgument(0), List.of()));

        cache.warmCacheOnStartup();

        int expected = MealRestaurant.values().length * 7;
        verify(mealConnector, times(expected)).fetchMeal(any(LocalDate.class), any(MealRestaurant.class));
        assertThat(cache.size()).isEqualTo(expected);
    }

    @Test
    void findReturnsValueAfterWarmUp() {
        LocalDate someMonday = LocalDate.of(2026, 5, 4);
        when(mealConnector.fetchMeal(any(LocalDate.class), any(MealRestaurant.class)))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(0);
                    MealRestaurant restaurant = invocation.getArgument(1);
                    return new MealResponse(
                            date,
                            List.of(new MealItem(
                                    restaurant.displayName(),
                                    MealType.LUNCH,
                                    "중식",
                                    List.of("밥"))));
                });

        cache.warmCacheOnStartup();

        assertThat(cache.find(LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                        .with(java.time.DayOfWeek.MONDAY), MealRestaurant.STUDENT))
                .isPresent()
                .get()
                .extracting(MealResponse::meals)
                .satisfies(meals -> assertThat((List<?>) meals).hasSize(1));
        assertThat(cache.find(someMonday.minusYears(1), MealRestaurant.STUDENT)).isEmpty();
    }

    @Test
    void failuresAreSkippedWithoutBreakingTheRefresh() {
        when(mealConnector.fetchMeal(any(LocalDate.class), any(MealRestaurant.class)))
                .thenAnswer(invocation -> {
                    MealRestaurant restaurant = invocation.getArgument(1);
                    if (restaurant == MealRestaurant.STUDENT) {
                        throw new ConnectorUnavailableException();
                    }
                    return new MealResponse(invocation.getArgument(0), List.of());
                });

        cache.warmCacheOnStartup();

        int expectedSuccess = (MealRestaurant.values().length - 1) * 7;
        assertThat(cache.size()).isEqualTo(expectedSuccess);
    }

    @Test
    void putAndFindRoundTrip() {
        LocalDate date = LocalDate.of(2026, 5, 7);
        MealResponse stored = new MealResponse(date, List.of());

        cache.put(date, MealRestaurant.STUDENT, stored);

        assertThat(cache.find(date, MealRestaurant.STUDENT)).contains(stored);
    }
}
