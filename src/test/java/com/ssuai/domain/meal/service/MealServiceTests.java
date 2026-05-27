package com.ssuai.domain.meal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ssuai.domain.meal.connector.MealConnector;
import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorUnavailableException;

class MealServiceTests {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 5, 6);

    private final MealConnector mealConnector = mock(MealConnector.class);
    private final MealService mealService = new MealService(mealConnector, Runnable::run, null);

    @Test
    void getTodayMealFetchesMealForTodayInSeoulTime() {
        when(mealConnector.fetchMeal(any(LocalDate.class), any(MealRestaurant.class)))
                .thenAnswer(invocation -> emptyResponse(invocation.getArgument(0)));

        LocalDate beforeCall = LocalDate.now(SEOUL_ZONE);
        mealService.getTodayMeal();
        LocalDate afterCall = LocalDate.now(SEOUL_ZONE);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(mealConnector, times(MealRestaurant.values().length))
                .fetchMeal(dateCaptor.capture(), any(MealRestaurant.class));
        assertThat(dateCaptor.getAllValues())
                .allSatisfy(captured -> assertThat(captured)
                        .isAfterOrEqualTo(beforeCall)
                        .isBeforeOrEqualTo(afterCall));
    }

    @Test
    void getMealFansOutToEveryRestaurantAndAggregatesResults() {
        when(mealConnector.fetchMeal(eq(DATE), any(MealRestaurant.class)))
                .thenAnswer(invocation -> {
                    MealRestaurant restaurant = invocation.getArgument(1);
                    return new MealResponse(
                            DATE,
                            List.of(new MealItem(
                                    restaurant.displayName(),
                                    MealType.LUNCH,
                                    "중식",
                                    List.of("밥", "국"))));
                });

        MealResponse response = mealService.getMeal(DATE);

        assertThat(response.date()).isEqualTo(DATE);
        assertThat(response.meals())
                .hasSize(MealRestaurant.values().length)
                .extracting(MealItem::restaurant)
                .containsExactly(
                        "학생식당", "숭실도담식당", "스낵코너", "푸드코트", "THE KITCHEN", "FACULTY LOUNGE");
        assertThat(response.closures()).isEmpty();
        verify(mealConnector, times(MealRestaurant.values().length))
                .fetchMeal(eq(DATE), any(MealRestaurant.class));
    }

    @Test
    void getMealFetchesRestaurantsInParallel() throws Exception {
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxActiveCalls = new AtomicInteger();
        CountDownLatch firstTwoCallsStarted = new CountDownLatch(2);
        CountDownLatch releaseCalls = new CountDownLatch(1);
        MealConnector slowConnector = (date, restaurant) -> {
            int currentActiveCalls = activeCalls.incrementAndGet();
            maxActiveCalls.accumulateAndGet(currentActiveCalls, Math::max);
            try {
                firstTwoCallsStarted.countDown();
                if (!releaseCalls.await(1, TimeUnit.SECONDS)) {
                    throw new ConnectorUnavailableException();
                }
                return new MealResponse(
                        date,
                        List.of(new MealItem(
                                restaurant.displayName(),
                                MealType.LUNCH,
                                "중식",
                                List.of("밥"))));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ConnectorUnavailableException(exception);
            } finally {
                activeCalls.decrementAndGet();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(MealRestaurant.values().length);
        ExecutorService callerExecutor = Executors.newSingleThreadExecutor();

        try {
            Future<MealResponse> responseFuture = callerExecutor
                    .submit(() -> new MealService(slowConnector, executor, null).getMeal(DATE));

            assertThat(firstTwoCallsStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maxActiveCalls.get()).isGreaterThan(1);
            releaseCalls.countDown();

            MealResponse response = responseFuture.get(1, TimeUnit.SECONDS);
            assertThat(response.meals()).hasSize(MealRestaurant.values().length);
        } finally {
            releaseCalls.countDown();
            callerExecutor.shutdownNow();
            executor.shutdownNow();
        }
    }

    @Test
    void getMealAbsorbsPartialFailureAsClosureAndReturnsRemainingMeals() {
        when(mealConnector.fetchMeal(DATE, MealRestaurant.STUDENT))
                .thenReturn(new MealResponse(
                        DATE,
                        List.of(new MealItem("학생식당", MealType.LUNCH, "중식", List.of("밥")))));
        when(mealConnector.fetchMeal(DATE, MealRestaurant.DODAM))
                .thenThrow(new ConnectorUnavailableException(new RuntimeException("503")));
        when(mealConnector.fetchMeal(DATE, MealRestaurant.SNACK))
                .thenReturn(new MealResponse(
                        DATE,
                        List.of(),
                        List.of(new MealClosure("스낵코너", "오늘은 쉽니다."))));
        for (MealRestaurant restaurant : List.of(
                MealRestaurant.FOOD_COURT, MealRestaurant.THE_KITCHEN, MealRestaurant.FACULTY_LOUNGE)) {
            when(mealConnector.fetchMeal(DATE, restaurant))
                    .thenReturn(new MealResponse(DATE, List.of()));
        }

        MealResponse response = mealService.getMeal(DATE);

        assertThat(response.meals())
                .extracting(MealItem::restaurant)
                .containsExactly("학생식당");
        assertThat(response.closures())
                .extracting(MealClosure::restaurant, MealClosure::reason)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("스낵코너", "오늘은 쉽니다."),
                        org.assertj.core.groups.Tuple.tuple("숭실도담식당", "조회 실패: CONNECTOR_UNAVAILABLE"));
    }

    @Test
    void getMealRethrowsWhenAllRestaurantsFail() {
        ConnectorParseException lastFailure = new ConnectorParseException();
        when(mealConnector.fetchMeal(eq(DATE), any(MealRestaurant.class)))
                .thenAnswer(invocation -> {
                    MealRestaurant restaurant = invocation.getArgument(1);
                    if (restaurant == MealRestaurant.FACULTY_LOUNGE) {
                        throw lastFailure;
                    }
                    throw new ConnectorUnavailableException();
                });

        assertThatThrownBy(() -> mealService.getMeal(DATE))
                .isSameAs(lastFailure);
    }

    private static MealResponse emptyResponse(LocalDate date) {
        return new MealResponse(date, List.of());
    }
}
