package com.ssuai.domain.meal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.meal.connector.MealConnector;
import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorUnavailableException;

class WeeklyMealServiceTests {

    private final MealConnector mealConnector = mock(MealConnector.class);
    private final MealService mealService = new MealService(mealConnector, Runnable::run, null);
    private final WeeklyMealService weeklyMealService = new WeeklyMealService(mealService, Runnable::run);

    @Test
    void fetchWeeklyMealsAggregatesAcrossSevenDaysWithFanOutFailures() {
        LocalDate startDate = LocalDate.of(2026, 5, 3);
        when(mealConnector.fetchMeal(any(LocalDate.class), any(MealRestaurant.class)))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(0);
                    MealRestaurant restaurant = invocation.getArgument(1);
                    if (restaurant == MealRestaurant.SNACK) {
                        throw new ConnectorUnavailableException(new RuntimeException("503"));
                    }
                    return new MealResponse(
                            date,
                            List.of(new MealItem(
                                    restaurant.displayName(),
                                    MealType.LUNCH,
                                    "중식",
                                    List.of("쌀밥"))));
                });

        WeeklyMealResponse response = weeklyMealService.fetchWeeklyMeals(startDate);

        assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 5, 9));
        assertThat(response.days()).hasSize(7);

        MealResponse firstDay = response.days().get(0);
        assertThat(firstDay.meals()).hasSize(MealRestaurant.values().length - 1);
        assertThat(firstDay.closures().get(0))
                .extracting(MealClosure::restaurant, MealClosure::reason)
                .containsExactly("스낵코너", "조회 실패: CONNECTOR_UNAVAILABLE");

        verify(mealConnector, times(7 * MealRestaurant.values().length))
                .fetchMeal(any(LocalDate.class), any(MealRestaurant.class));
    }

    @Test
    void fetchWeeklyMealsRunsDayFetchesConcurrentlyAndKeepsDateOrder() throws Exception {
        LocalDate startDate = LocalDate.of(2026, 5, 4);
        MealService slowMealService = mock(MealService.class);
        ExecutorService weeklyExecutor = Executors.newFixedThreadPool(7);
        CountDownLatch atLeastTwoDaysStarted = new CountDownLatch(2);
        CountDownLatch releaseFetches = new CountDownLatch(1);

        try {
            when(slowMealService.getMeal(any(LocalDate.class)))
                    .thenAnswer(invocation -> {
                        LocalDate date = invocation.getArgument(0);
                        atLeastTwoDaysStarted.countDown();
                        assertThat(releaseFetches.await(1, TimeUnit.SECONDS)).isTrue();
                        return new MealResponse(date, List.of(), List.of());
                    });
            WeeklyMealService service = new WeeklyMealService(slowMealService, weeklyExecutor);

            CompletableFuture<WeeklyMealResponse> responseFuture = CompletableFuture.supplyAsync(
                    () -> service.fetchWeeklyMeals(startDate));

            assertThat(atLeastTwoDaysStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseFetches.countDown();

            WeeklyMealResponse response = responseFuture.get(1, TimeUnit.SECONDS);

            assertThat(response.days())
                    .extracting(MealResponse::date)
                    .containsExactlyElementsOf(IntStream.range(0, 7)
                            .mapToObj(startDate::plusDays)
                            .toList());
        } finally {
            weeklyExecutor.shutdownNow();
        }
    }

    @Test
    void fetchWeeklyMealsPropagatesMealServiceFailuresWithoutCompletionWrapper() {
        LocalDate startDate = LocalDate.of(2026, 5, 4);
        MealService failingMealService = mock(MealService.class);
        ConnectorUnavailableException failure = new ConnectorUnavailableException(new RuntimeException("503"));
        when(failingMealService.getMeal(any(LocalDate.class))).thenThrow(failure);

        WeeklyMealService service = new WeeklyMealService(failingMealService, Runnable::run);

        assertThatThrownBy(() -> service.fetchWeeklyMeals(startDate))
                .isSameAs(failure);
    }
}
