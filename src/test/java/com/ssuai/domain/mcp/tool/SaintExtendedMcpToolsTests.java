package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GpaSimulationResponse;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.ScholarshipEntry;
import com.ssuai.domain.saint.service.SaintChapelService;
import com.ssuai.domain.saint.service.SaintGpaSimulationService;
import com.ssuai.domain.saint.service.SaintGraduationService;
import com.ssuai.domain.saint.service.SaintScholarshipService;

class SaintExtendedMcpToolsTests {

    private static final String SESSION_ID = "test-session";
    private static final String STUDENT_ID = "20221528";

    private McpAuthHelper authHelper;
    private SaintChapelService chapelService;
    private SaintGraduationService graduationService;
    private SaintScholarshipService scholarshipService;
    private SaintGpaSimulationService gpaSimulationService;
    private SaintExtendedMcpTools tools;

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        chapelService = mock(SaintChapelService.class);
        graduationService = mock(SaintGraduationService.class);
        scholarshipService = mock(SaintScholarshipService.class);
        gpaSimulationService = mock(SaintGpaSimulationService.class);
        tools = new SaintExtendedMcpTools(
                chapelService, graduationService, scholarshipService, gpaSimulationService, authHelper);
    }

    @Test
    void returnsAuthRequiredWithoutCallingServices() {
        McpPrivateToolResponse<ChapelInfo> stub = McpPrivateToolResponse.authRequired(
                null, "SAINT", "https://login.url", Instant.parse("2026-05-23T15:00:00Z"));
        when(authHelper.resolvePrincipal(null, McpProviderType.SAINT)).thenReturn(Optional.empty());
        when(authHelper.<ChapelInfo>buildAuthRequired(null, McpProviderType.SAINT)).thenReturn(stub);

        McpPrivateToolResponse<ChapelInfo> response = tools.getMyChapelInfo(null, null, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(chapelService, graduationService, scholarshipService, gpaSimulationService);
    }

    @Test
    void chapelToolPassesOptionalTermSelection() {
        ChapelInfo stub = new ChapelInfo(2025, "2학기", "", "", null, null, 0, "", List.of(), List.of());
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(STUDENT_ID, SESSION_ID)));
        when(chapelService.fetchChapelInfo(STUDENT_ID, 2025, "2학기")).thenReturn(stub);

        McpPrivateToolResponse<ChapelInfo> response =
                tools.getMyChapelInfo(2025, "2학기", SESSION_ID);

        assertThat(response.data()).isSameAs(stub);
        verify(chapelService).fetchChapelInfo(STUDENT_ID, 2025, "2학기");
    }

    @Test
    void graduationToolDelegatesForLinkedSession() {
        GraduationStatus stub = new GraduationStatus(false, "", "", 3, 100, 133, List.of());
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(STUDENT_ID, SESSION_ID)));
        when(graduationService.fetchGraduationRequirements(STUDENT_ID)).thenReturn(stub);

        McpPrivateToolResponse<GraduationStatus> response =
                tools.checkGraduationRequirements(SESSION_ID);

        assertThat(response.data()).isSameAs(stub);
        verify(graduationService).fetchGraduationRequirements(STUDENT_ID);
    }

    @Test
    void scholarshipToolPassesOptionalYear() {
        List<ScholarshipEntry> stub = List.of(
                new ScholarshipEntry(2025, "2학기", "장학금", 100, "지급", "완료"));
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(STUDENT_ID, SESSION_ID)));
        when(scholarshipService.fetchScholarships(STUDENT_ID, 2025)).thenReturn(stub);

        McpPrivateToolResponse<List<ScholarshipEntry>> response =
                tools.getMyScholarships(2025, SESSION_ID);

        assertThat(response.data()).isSameAs(stub);
        verify(scholarshipService).fetchScholarships(STUDENT_ID, 2025);
        verify(chapelService, never()).fetchChapelInfo(any(), any(), any());
    }

    @Test
    void gpaSimulationToolDelegatesForLinkedSession() {
        GpaSimulationResponse stub = new GpaSimulationResponse(
                3.32d, 69.0d, 229.2d, 18.0d, 4.0d, 3.4621d, 3.45d, 3.9417d, true, 4.5d, 3.5655d);
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(STUDENT_ID, SESSION_ID)));
        when(gpaSimulationService.simulate(STUDENT_ID, 18.0d, 4.0d, 3.45d)).thenReturn(stub);

        McpPrivateToolResponse<GpaSimulationResponse> response =
                tools.simulateGpa(18.0d, 4.0d, 3.45d, SESSION_ID);

        assertThat(response.data()).isSameAs(stub);
        verify(gpaSimulationService).simulate(STUDENT_ID, 18.0d, 4.0d, 3.45d);
    }
}
