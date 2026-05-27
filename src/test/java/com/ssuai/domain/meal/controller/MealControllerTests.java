package com.ssuai.domain.meal.controller;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.domain.meal.service.MealService;
import com.ssuai.domain.meal.service.WeeklyMealService;

@ActiveProfiles("test")
@WebMvcTest(MealController.class)
class MealControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private MealService mealService;

    @MockitoBean
    private WeeklyMealService weeklyMealService;

    @Autowired
    MealControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getTodayMealReturnsSuccessEnvelope() throws Exception {
        MealResponse response = new MealResponse(
                LocalDate.of(2026, 5, 6),
                List.of(new MealItem("학생식당", MealType.BREAKFAST, "조식", List.of("쌀밥", "미역국")))
        );
        when(mealService.getTodayMeal()).thenReturn(response);

        mockMvc.perform(get("/api/meals/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.data.meals[0].restaurant").value("학생식당"))
                .andExpect(jsonPath("$.data.meals[0].type").value("BREAKFAST"))
                .andExpect(jsonPath("$.data.meals[0].corner").value("조식"))
                .andExpect(jsonPath("$.data.meals[0].menu").value(not(empty())))
                .andExpect(jsonPath("$.data.closures").value(empty()))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }

    @Test
    void getWeeklyMealsWithStartDateReturnsSuccessEnvelope() throws Exception {
        LocalDate startDate = LocalDate.of(2026, 5, 4);
        WeeklyMealResponse response = new WeeklyMealResponse(startDate, LocalDate.of(2026, 5, 10), List.of());
        when(weeklyMealService.fetchWeeklyMeals(startDate)).thenReturn(response);

        mockMvc.perform(get("/api/meals/weekly").param("startDate", "2026-05-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-05-04"))
                .andExpect(jsonPath("$.data.endDate").value("2026-05-10"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));

        verify(weeklyMealService).fetchWeeklyMeals(startDate);
    }

    @Test
    void getWeeklyMealsWithoutStartDateDefaultsToMonday() throws Exception {
        when(weeklyMealService.fetchWeeklyMeals(any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    LocalDate startDate = invocation.getArgument(0);
                    return new WeeklyMealResponse(startDate, startDate.plusDays(6), List.of());
                });

        mockMvc.perform(get("/api/meals/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.data.endDate").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.error").value(nullValue()));

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(weeklyMealService).fetchWeeklyMeals(captor.capture());
        LocalDate resolved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(resolved.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void getWeeklyMealsWithInvalidStartDateReturnsValidationError() throws Exception {
        mockMvc.perform(get("/api/meals/weekly").param("startDate", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getWeeklyMealsRejectsOversizedStartDate() throws Exception {
        mockMvc.perform(get("/api/meals/weekly").param("startDate", "2026-05-04-extra"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
