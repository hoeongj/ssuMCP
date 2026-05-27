package com.ssuai.domain.saint.controller;

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

import com.ssuai.domain.saint.dto.ScheduleEntry;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.TermSchedule;
import com.ssuai.domain.saint.service.SaintScheduleService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.SaintSessionExpiredException;

@ActiveProfiles("test")
@WebMvcTest(SaintScheduleController.class)
class SaintScheduleControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private SaintScheduleService scheduleService;

    @Autowired
    SaintScheduleControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsScheduleEnvelopeWhenStudentIdPresent() throws Exception {
        ScheduleResponse payload = new ScheduleResponse(2024, 2026, 1, List.of(
                new TermSchedule(2026, 1, List.of(
                        new ScheduleEntry(1, "월", 3, "10:30-11:45",
                                "자료구조", "김교수", "정보과학관 30100"))),
                new TermSchedule(2025, 1, List.of()),
                new TermSchedule(2024, 1, List.of())));
        when(scheduleService.fetchSchedule("20241234")).thenReturn(payload);

        mockMvc.perform(get("/api/saint/schedule")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enrollmentYear").value(2024))
                .andExpect(jsonPath("$.data.currentYear").value(2026))
                .andExpect(jsonPath("$.data.currentTerm").value(1))
                .andExpect(jsonPath("$.data.terms.length()").value(3))
                .andExpect(jsonPath("$.data.terms[0].entries[0].course").value("자료구조"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void returns401UnauthorizedWhenStudentIdAttributeMissing() throws Exception {
        mockMvc.perform(get("/api/saint/schedule"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(scheduleService);
    }

    @Test
    void returns401UnauthorizedWhenStudentIdAttributeBlank() throws Exception {
        mockMvc.perform(get("/api/saint/schedule")
                        .requestAttr(AuthAttributes.STUDENT_ID, "   "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(scheduleService);
    }

    @Test
    void returns401SaintSessionExpiredWhenStoreHasNoCookies() throws Exception {
        when(scheduleService.fetchSchedule("20241234"))
                .thenThrow(new SaintSessionExpiredException());

        mockMvc.perform(get("/api/saint/schedule")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SAINT_SESSION_EXPIRED"));
    }
}
