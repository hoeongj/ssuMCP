package com.ssuai.domain.meal.service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;

@Service
public class WeeklyMealService {

    private static final int DAYS_PER_WEEK = 7;

    private final MealService mealService;
    private final Executor weeklyMealFanOutExecutor;

    public WeeklyMealService(
            MealService mealService,
            @Qualifier("weeklyMealFanOutExecutor") Executor weeklyMealFanOutExecutor
    ) {
        this.mealService = mealService;
        this.weeklyMealFanOutExecutor = weeklyMealFanOutExecutor;
    }

    public WeeklyMealResponse fetchWeeklyMeals(LocalDate startDate) {
        List<CompletableFuture<MealResponse>> futures = IntStream.range(0, DAYS_PER_WEEK)
                .mapToObj(dayOffset -> CompletableFuture.supplyAsync(
                        () -> mealService.getMeal(startDate.plusDays(dayOffset)),
                        weeklyMealFanOutExecutor))
                .toList();

        List<MealResponse> days = futures.stream()
                .map(this::joinMeal)
                .toList();

        return new WeeklyMealResponse(startDate, startDate.plusDays(DAYS_PER_WEEK - 1), days);
    }

    private MealResponse joinMeal(CompletableFuture<MealResponse> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }
}
