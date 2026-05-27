package com.ssuai.domain.saint.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.saint.dto.CourseGrade;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.TermGpa;
import com.ssuai.domain.saint.service.SaintGradesService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.exception.SaintSessionExpiredException;

@ActiveProfiles("test")
@WebMvcTest(SaintGradesController.class)
class SaintGradesControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private SaintGradesService gradesService;

    @Autowired
    SaintGradesControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void returnsGradesEnvelopeWhenStudentIdPresent() throws Exception {
        GpaSummary academic = new GpaSummary(75.0d, 75.0d, 262.5d, 3.5d, 85.0d, 12.0d);
        GpaSummary cert = new GpaSummary(72.0d, 72.0d, 252.0d, 3.5d, 85.0d, 12.0d);
        GradesResponse payload = new GradesResponse(
                List.of(new TermGpa(2026, "1학기", 18.0d, 18.0d, 3.0d, 3.5d, 63.0d, 85.0d,
                        "50/100", "60/100", false, false, false)),
                academic, cert,
                Map.of("2026-1학기", List.of(
                        new CourseGrade("95", "A0", "과목A", "21500001", 3.0d, "김교수", ""))));
        when(gradesService.fetchGrades("20241234")).thenReturn(payload);

        mockMvc.perform(get("/api/saint/grades")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history.length()").value(1))
                .andExpect(jsonPath("$.data.history[0].year").value(2026))
                .andExpect(jsonPath("$.data.history[0].term").value("1학기"))
                .andExpect(jsonPath("$.data.academicRecord.gpa").value(3.5))
                .andExpect(jsonPath("$.data.certificate.gpa").value(3.5))
                .andExpect(jsonPath("$.data.detailsByTerm['2026-1학기'][0].courseName").value("과목A"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void returns401UnauthorizedWhenStudentIdAttributeMissing() throws Exception {
        mockMvc.perform(get("/api/saint/grades"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(gradesService);
    }

    @Test
    void returns401UnauthorizedWhenStudentIdAttributeBlank() throws Exception {
        mockMvc.perform(get("/api/saint/grades")
                        .requestAttr(AuthAttributes.STUDENT_ID, "   "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        verifyNoInteractions(gradesService);
    }

    @Test
    void returns401SaintSessionExpiredWhenStoreHasNoCookies() throws Exception {
        when(gradesService.fetchGrades("20241234"))
                .thenThrow(new SaintSessionExpiredException());

        mockMvc.perform(get("/api/saint/grades")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SAINT_SESSION_EXPIRED"));
    }
}
