package com.ssuai.domain.dorm.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.dorm.service.DormMealService;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/dorm/meals")
@Tag(name = "Dorm meals", description = "Dormitory weekly meal lookup API")
public class DormMealController {

    private final DormMealService dormMealService;

    public DormMealController(DormMealService dormMealService) {
        this.dormMealService = dormMealService;
    }

    @GetMapping("/this-week")
    @Operation(summary = "Get this week's dormitory meals")
    public ApiResponse<WeeklyMealResponse> getThisWeekMeal() {
        return ApiResponse.success(dormMealService.getThisWeekMeal());
    }
}
