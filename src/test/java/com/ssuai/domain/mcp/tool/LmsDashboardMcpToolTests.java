package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.LmsDashboardResponse;
import com.ssuai.domain.lms.service.LmsDashboardService;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

class LmsDashboardMcpToolTests {

    private McpAuthHelper authHelper;
    private LmsDashboardService dashboardService;
    private LmsDashboardMcpTool tool;

    private static final String SESSION_ID = "test-session-lms";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        dashboardService = mock(LmsDashboardService.class);
        tool = new LmsDashboardMcpTool(authHelper, dashboardService);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getLmsDashboard(null, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(resp.provider()).isEqualTo("LMS");
        assertThat(resp.data()).isNull();
        verify(dashboardService, never()).getDashboard(any(), any());
    }

    @Test
    void returnsAuthRequiredWhenLmsNotLinked() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(SESSION_ID, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getLmsDashboard(SESSION_ID, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(dashboardService, never()).getDashboard(any(), any());
    }

    @Test
    void returnsOkWithDataWhenLinked() {
        LmsDashboardResponse stub = new LmsDashboardResponse(List.of(), List.of(), List.of(), "2026 1학기", "");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(dashboardService.getDashboard("20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getLmsDashboard(SESSION_ID, null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isSameAs(stub);
        verify(dashboardService).getDashboard("20221528", null);
    }

    @Test
    void returnsAuthRequiredWhenSessionExpired() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(dashboardService.getDashboard("20221528", null)).thenThrow(new LmsSessionExpiredException());

        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LMS", "https://login.url", EXPIRES);
        when(authHelper.<Object>buildAuthRequired(SESSION_ID, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getLmsDashboard(SESSION_ID, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(dashboardService).getDashboard("20221528", null);
    }

    @Test
    void mapsLmsApiFailureToStructuredUpstreamOutcome() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(
                        "20221528", SESSION_ID)));
        when(dashboardService.getDashboard("20221528", null))
                .thenThrow(new LmsApiException("private upstream response", 502));

        McpPrivateToolResponse<Object> response = tool.getLmsDashboard(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.retryable()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.toString()).doesNotContain("private upstream response");
    }

    @Test
    void responseDoesNotContainStudentId() {
        LmsDashboardResponse stub = new LmsDashboardResponse(List.of(), List.of(), List.of(), "2026 1학기", "");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(dashboardService.getDashboard("20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getLmsDashboard(SESSION_ID, null);

        assertThat(resp.toString()).doesNotContain("20221528");
        assertThat(resp.provider()).isEqualTo("LMS");
    }
}
