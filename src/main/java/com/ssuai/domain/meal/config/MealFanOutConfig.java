package com.ssuai.domain.meal.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.ssuai.domain.meal.dto.MealRestaurant;

@Configuration
public class MealFanOutConfig {

    private static final int WEEKLY_MEAL_DAYS = 7;

    @Bean
    Executor mealFanOutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MealRestaurant.values().length);
        executor.setMaxPoolSize(MealRestaurant.values().length);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("meal-fanout-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    Executor weeklyMealFanOutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(WEEKLY_MEAL_DAYS);
        executor.setMaxPoolSize(WEEKLY_MEAL_DAYS);
        executor.setQueueCapacity(WEEKLY_MEAL_DAYS * 2);
        executor.setThreadNamePrefix("weekly-meal-fanout-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
