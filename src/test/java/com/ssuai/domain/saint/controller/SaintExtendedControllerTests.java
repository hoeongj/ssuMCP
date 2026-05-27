package com.ssuai.domain.saint.controller;

import static org.mockito.Mockito.verify;
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

import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.ChapelAbsenceApplication;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.domain.saint.service.SaintChapelService;
import com.ssuai.domain.saint.service.SaintGraduationService;
import com.ssuai.domain.saint.service.SaintScholarshipService;
import com.ssuai.global.auth.AuthAttributes;

@ActiveProfiles("test")
@WebMvcTest({
        SaintChapelController.class,
        SaintGraduationController.class,
        SaintScholarshipController.class
})
class SaintExtendedControllerTests {

    private final MockMvc mockMvc;

    @MockitoBean
    private SaintChapelService chapelService;

    @MockitoBean
    private SaintGraduationService graduationService;

    @MockitoBean
    private SaintScholarshipService scholarshipService;

    @Autowired
    SaintExtendedControllerTests(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void chapelEndpointForwardsTermSelection() throws Exception {
        when(chapelService.fetchChapelInfo("20241234", 2025, "2학기"))
                .thenReturn(new ChapelInfo(
                        2025, "2학기", "", "", "J-5-5", null, 0, "", List.of(),
                        List.of(new ChapelAbsenceApplication(
                                "병무관계", "2025.05.14", "2025.05.20", "예비군", "승인"))));

        mockMvc.perform(get("/api/saint/chapel")
                        .param("year", "2025")
                        .param("semester", "2학기")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(2025))
                .andExpect(jsonPath("$.data.semester").value("2학기"))
                .andExpect(jsonPath("$.data.seatNumber").value("J-5-5"))
                .andExpect(jsonPath("$.data.absenceApplications[0].reason").value("예비군"))
                .andExpect(jsonPath("$.data.absenceApplications[0].status").value("승인"));

        verify(chapelService).fetchChapelInfo("20241234", 2025, "2학기");
    }

    @Test
    void graduationEndpointReturnsRequirementStatus() throws Exception {
        when(graduationService.fetchGraduationRequirements("20241234"))
                .thenReturn(new GraduationStatus(false, "", "", 3, 100, 133, List.of()));

        mockMvc.perform(get("/api/saint/graduation")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isGraduatable").value(false));
    }

    @Test
    void scholarshipEndpointForwardsOptionalYear() throws Exception {
        when(scholarshipService.fetchScholarships("20241234", 2025))
                .thenReturn(List.of(new ScholarshipEntry(2025, "2학기", "장학금", 1000, "지급", "완료")));

        mockMvc.perform(get("/api/saint/scholarships")
                        .param("year", "2025")
                        .requestAttr(AuthAttributes.STUDENT_ID, "20241234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].year").value(2025));
    }

    @Test
    void endpointsRequireAuthenticatedStudentId() throws Exception {
        mockMvc.perform(get("/api/saint/chapel"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/saint/graduation"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/saint/scholarships"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(chapelService, graduationService, scholarshipService);
    }
}
