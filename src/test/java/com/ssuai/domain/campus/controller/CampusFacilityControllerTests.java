package com.ssuai.domain.campus.controller;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.campus.dto.CampusFacilityCategory;
import com.ssuai.domain.campus.dto.CampusFacilityListResponse;
import com.ssuai.domain.campus.dto.CampusFacilityResponse;
import com.ssuai.domain.campus.service.CampusFacilityService;

@ActiveProfiles("test")
@WebMvcTest(CampusFacilityController.class)
class CampusFacilityControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private CampusFacilityService campusFacilityService;

    @Autowired
    CampusFacilityControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void getFacilitiesReturnsSuccessEnvelope() throws Exception {
        CampusFacilityListResponse response = new CampusFacilityListResponse(List.of(
                new CampusFacilityResponse(
                        "student-cafeteria",
                        "학생식당",
                        CampusFacilityCategory.CAFETERIA,
                        "식당",
                        "학생회관 3층",
                        "820-0882",
                        "0882",
                        null,
                        List.of("08:00~09:00 (천원의아침밥)", "11:20~14:00 (식사 제공)"),
                        List.of("휴무 (숭실도담 이용 바람)"),
                        List.of(),
                        List.of("학식")
                )
        ));
        when(campusFacilityService.searchFacilities("학생회관")).thenReturn(response);

        mockMvc.perform(get("/api/campus/facilities").param("query", "학생회관"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilities[0].id").value("student-cafeteria"))
                .andExpect(jsonPath("$.data.facilities[0].name").value("학생식당"))
                .andExpect(jsonPath("$.data.facilities[0].category").value("CAFETERIA"))
                .andExpect(jsonPath("$.data.facilities[0].categoryLabel").value("식당"))
                .andExpect(jsonPath("$.data.facilities[0].location").value("학생회관 3층"))
                .andExpect(jsonPath("$.data.facilities[0].phone").value("820-0882"))
                .andExpect(jsonPath("$.data.facilities[0].extension").value("0882"))
                .andExpect(jsonPath("$.data.facilities[0].weekdayHours").value(not(empty())))
                .andExpect(jsonPath("$.data.facilities[0].weekendHours").value(not(empty())))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }

    @Test
    void getFacilitiesRejectsOversizedQuery() throws Exception {
        mockMvc.perform(get("/api/campus/facilities").param("query", "a".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));

        verifyNoInteractions(campusFacilityService);
    }
}
