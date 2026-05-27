package com.ssuai.domain.meal.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.meal.connector.MealConnector;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.global.exception.ConnectorException;

/**
 * 주 1회(월요일 06:00 KST) + 애플리케이션 시작 시 학식 데이터를 학교 페이지에서 긁어와
 * (date, restaurant) 쌍을 키로 보관한다. 캐시 미스는 service 계층에서 connector 직접 호출로
 * 처리하므로, 이 캐시가 비어 있어도 챗봇/REST API는 정상 동작한다.
 */
@Component
public class WeeklyMealCache {

    private static final Logger log = LoggerFactory.getLogger(WeeklyMealCache.class);
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final int DAYS_PER_WEEK = 7;

    private final MealConnector mealConnector;
    private final Executor mealFanOutExecutor;
    private final ConcurrentMap<CacheKey, MealResponse> entries = new ConcurrentHashMap<>();

    public WeeklyMealCache(
            MealConnector mealConnector,
            @Qualifier("mealFanOutExecutor") Executor mealFanOutExecutor
    ) {
        this.mealConnector = mealConnector;
        this.mealFanOutExecutor = mealFanOutExecutor;
    }

    @PostConstruct
    void warmCacheOnStartup() {
        LocalDate today = LocalDate.now(SEOUL_ZONE);
        log.info("meal cache warm-up starting: weekOf={}", mondayOf(today));
        refreshWeek(today);
    }

    @Scheduled(cron = "0 0 6 ? * MON", zone = "Asia/Seoul")
    public void refreshWeekly() {
        LocalDate today = LocalDate.now(SEOUL_ZONE);
        log.info("meal cache scheduled refresh: weekOf={}", mondayOf(today));
        refreshWeek(today);
    }

    public Optional<MealResponse> find(LocalDate date, MealRestaurant restaurant) {
        return Optional.ofNullable(entries.get(new CacheKey(date, restaurant)));
    }

    public void put(LocalDate date, MealRestaurant restaurant, MealResponse response) {
        entries.put(new CacheKey(date, restaurant), response);
    }

    int size() {
        return entries.size();
    }

    private void refreshWeek(LocalDate anyDateInWeek) {
        LocalDate monday = mondayOf(anyDateInWeek);
        List<CompletableFuture<Void>> tasks = Arrays.stream(MealRestaurant.values())
                .flatMap(restaurant -> java.util.stream.IntStream.range(0, DAYS_PER_WEEK)
                        .mapToObj(offset -> CompletableFuture.runAsync(
                                () -> refreshOne(monday.plusDays(offset), restaurant),
                                mealFanOutExecutor)))
                .toList();
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            log.info("meal cache refresh completed: weekOf={} entries={}", monday, entries.size());
        } catch (RuntimeException exception) {
            log.warn("meal cache refresh partially failed: weekOf={} error={}",
                    monday, exception.getClass().getSimpleName());
        }
    }

    private void refreshOne(LocalDate date, MealRestaurant restaurant) {
        try {
            MealResponse fresh = mealConnector.fetchMeal(date, restaurant);
            entries.put(new CacheKey(date, restaurant), fresh);
        } catch (ConnectorException exception) {
            log.warn("meal cache refresh skipped: date={} restaurant={} code={}",
                    date, restaurant.displayName(), exception.getErrorCode().name());
        } catch (RuntimeException exception) {
            log.warn("meal cache refresh skipped: date={} restaurant={} error={}",
                    date, restaurant.displayName(), exception.getClass().getSimpleName());
        }
    }

    private static LocalDate mondayOf(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    private record CacheKey(LocalDate date, MealRestaurant restaurant) {
    }
}
