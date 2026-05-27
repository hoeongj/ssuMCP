package com.ssuai.domain.dorm.connector;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;

@Component
@ConditionalOnProperty(name = "ssuai.connector.dorm-meal", havingValue = "mock", matchIfMissing = true)
public class MockDormMealConnector implements DormMealConnector {

    static final String RESTAURANT = "레지던스홀 기숙사 식당";
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    @Override
    public WeeklyMealResponse fetchThisWeekMeal() {
        LocalDate weekStart = LocalDate.now(SEOUL_ZONE).with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        List<MealResponse> days = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            days.add(new MealResponse(
                    date,
                    List.of(
                            new MealItem(RESTAURANT, MealType.LUNCH, "중식",
                                    List.of("쌀밥", "된장찌개", "제육볶음", "콩나물무침", "배추김치")),
                            new MealItem(RESTAURANT, MealType.DINNER, "석식",
                                    List.of("쌀밥", "김치찌개", "고등어구이", "시금치나물", "배추김치"))),
                    List.of(new MealClosure(RESTAURANT, "조식 미운영"))));
        }
        return new WeeklyMealResponse(weekStart, weekEnd, days);
    }
}
