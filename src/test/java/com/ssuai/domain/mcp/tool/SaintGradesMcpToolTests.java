package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.service.SaintGradesService;

class SaintGradesMcpToolTests {

    private McpAuthHelper authHelper;
    private SaintGradesService gradesService;
    private SaintGradesMcpTool tool;

    private static final String SESSION_ID = "test-session";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        gradesService = mock(SaintGradesService.class);
        tool = new SaintGradesMcpTool(gradesService, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<GradesResponse> stub =
                McpPrivateToolResponse.authRequired(null, "SAINT", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.SAINT)).thenReturn(Optional.empty());
        when(authHelper.<GradesResponse>buildAuthRequired(null, McpProviderType.SAINT)).thenReturn(stub);

        McpPrivateToolResponse<GradesResponse> resp = tool.getMyGrades(null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(resp.data()).isNull();
        verify(gradesService, never()).fetchGrades(any());
    }

    @Test
    void returnsAuthRequiredWhenSaintNotLinked() {
        McpPrivateToolResponse<GradesResponse> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "SAINT", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.SAINT)).thenReturn(Optional.empty());
        when(authHelper.<GradesResponse>buildAuthRequired(SESSION_ID, McpProviderType.SAINT)).thenReturn(stub);

        McpPrivateToolResponse<GradesResponse> resp = tool.getMyGrades(SESSION_ID);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(gradesService, never()).fetchGrades(any());
    }

    @Test
    void returnsOkWithDataWhenLinked() {
        GpaSummary zero = new GpaSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        GradesResponse stub = new GradesResponse(List.of(), zero, zero, Map.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of("20221528"));
        when(gradesService.fetchGrades("20221528")).thenReturn(stub);

        McpPrivateToolResponse<GradesResponse> resp = tool.getMyGrades(SESSION_ID);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isSameAs(stub);
        assertThat(resp.loginUrl()).isNull();
        verify(gradesService).fetchGrades("20221528");
    }

    @Test
    void responseDoesNotContainStudentId() {
        GpaSummary zero = new GpaSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        GradesResponse stub = new GradesResponse(List.of(), zero, zero, Map.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of("20221528"));
        when(gradesService.fetchGrades("20221528")).thenReturn(stub);

        McpPrivateToolResponse<GradesResponse> resp = tool.getMyGrades(SESSION_ID);

        assertThat(resp.toString()).doesNotContain("20221528");
    }
}
