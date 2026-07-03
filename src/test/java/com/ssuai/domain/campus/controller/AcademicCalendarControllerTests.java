package com.ssuai.domain.campus.controller;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.domain.campus.service.AcademicCalendarService;

@ActiveProfiles("test")
@WebMvcTest(AcademicCalendarController.class)
class AcademicCalendarControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private AcademicCalendarService academicCalendarService;

    @Autowired
    AcademicCalendarControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsCalendarEnvelopeForRequestedYear() throws Exception {
        when(academicCalendarService.getCalendar(2026)).thenReturn(List.of(
                new AcademicCalendarEvent("2026-03-02", null, "1학기 개강", ""),
                new AcademicCalendarEvent("2026-06-19", "2026-06-25", "1학기 종강", "")));

        mockMvc.perform(get("/api/academic-calendar").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(2026))
                .andExpect(jsonPath("$.data.events[0].date").value("2026-03-02"))
                .andExpect(jsonPath("$.data.events[0].event").value("1학기 개강"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())));
    }

    @Test
    void defaultsToCurrentYearWhenYearOmitted() throws Exception {
        int currentYear = LocalDate.now(ZoneId.of("Asia/Seoul")).getYear();
        when(academicCalendarService.getCalendar(currentYear)).thenReturn(List.of());

        mockMvc.perform(get("/api/academic-calendar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(currentYear));

        verify(academicCalendarService).getCalendar(eq(currentYear));
    }

    @Test
    void rejectsOutOfRangeYear() throws Exception {
        mockMvc.perform(get("/api/academic-calendar").param("year", "1999"))
                .andExpect(status().isBadRequest());
    }
}
