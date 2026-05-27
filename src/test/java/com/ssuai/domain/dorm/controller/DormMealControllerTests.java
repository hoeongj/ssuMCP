package com.ssuai.domain.dorm.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.dorm.service.DormMealService;
import com.ssuai.domain.meal.dto.MealClosure;
import com.ssuai.domain.meal.dto.MealItem;
import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealType;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;

@ActiveProfiles("test")
@WebMvcTest(DormMealController.class)
class DormMealControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private DormMealService dormMealService;

    @Autowired
    DormMealControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getThisWeekMealReturnsSuccessEnvelope() throws Exception {
        WeeklyMealResponse response = new WeeklyMealResponse(
                LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 5, 10),
                List.of(new MealResponse(
                        LocalDate.of(2026, 5, 4),
                        List.of(new MealItem(
                                "레지던스홀 기숙사 식당", MealType.LUNCH, "중식", List.of("쌀밥", "된장찌개"))),
                        List.of(new MealClosure("레지던스홀 기숙사 식당", "조식 미운영")))));
        when(dormMealService.getThisWeekMeal()).thenReturn(response);

        mockMvc.perform(get("/api/dorm/meals/this-week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-05-04"))
                .andExpect(jsonPath("$.data.endDate").value("2026-05-10"))
                .andExpect(jsonPath("$.data.days[0].date").value("2026-05-04"))
                .andExpect(jsonPath("$.data.days[0].meals[0].restaurant").value("레지던스홀 기숙사 식당"))
                .andExpect(jsonPath("$.data.days[0].meals[0].type").value("LUNCH"))
                .andExpect(jsonPath("$.data.days[0].meals[0].corner").value("중식"))
                .andExpect(jsonPath("$.data.days[0].closures[0].restaurant").value("레지던스홀 기숙사 식당"))
                .andExpect(jsonPath("$.data.days[0].closures[0].reason").value("조식 미운영"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }
}
