package com.ssuai.domain.meal.controller;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.domain.meal.service.MealService;
import com.ssuai.domain.meal.service.WeeklyMealService;
import com.ssuai.global.response.ApiResponse;

@Validated
@RestController
@RequestMapping("/api/meals")
@Tag(name = "Meals", description = "Cafeteria meal lookup API")
public class MealController {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final int ISO_DATE_LENGTH = 10;

    private final MealService mealService;
    private final WeeklyMealService weeklyMealService;

    public MealController(MealService mealService, WeeklyMealService weeklyMealService) {
        this.mealService = mealService;
        this.weeklyMealService = weeklyMealService;
    }

    @GetMapping("/today")
    @Operation(summary = "Get today's cafeteria meals")
    public ApiResponse<MealResponse> getTodayMeal() {
        return ApiResponse.success(mealService.getTodayMeal());
    }

    @GetMapping("/weekly")
    @Operation(summary = "Get weekly cafeteria meals")
    public ApiResponse<WeeklyMealResponse> getWeeklyMeals(
            @RequestParam(required = false)
            @Size(max = ISO_DATE_LENGTH)
            @Parameter(description = "Optional week start date in yyyy-MM-dd format. Defaults to this week's Monday.")
            String startDate
    ) {
        LocalDate resolved = resolveStartDate(startDate);
        return ApiResponse.success(weeklyMealService.fetchWeeklyMeals(resolved));
    }

    private LocalDate resolveStartDate(String startDate) {
        if (startDate == null || startDate.isBlank()) {
            return LocalDate.now(SEOUL_ZONE).with(DayOfWeek.MONDAY);
        }

        try {
            return LocalDate.parse(startDate);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "startDate는 yyyy-MM-dd 형식이어야 합니다. 받은 값: '" + startDate + "'.",
                    exception);
        }
    }
}
