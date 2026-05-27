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
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.service.LmsAssignmentsService;

class LmsAssignmentsMcpToolTests {

    private McpAuthHelper authHelper;
    private LmsAssignmentsService assignmentsService;
    private LmsAssignmentsMcpTool tool;

    private static final String SESSION_ID = "test-session-lms";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        assignmentsService = mock(LmsAssignmentsService.class);
        tool = new LmsAssignmentsMcpTool(assignmentsService, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<AssignmentsResponse> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<AssignmentsResponse>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<AssignmentsResponse> resp = tool.getMyAssignments(null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(resp.provider()).isEqualTo("LMS");
        assertThat(resp.data()).isNull();
        verify(assignmentsService, never()).fetchAssignments(any());
    }

    @Test
    void returnsAuthRequiredWhenLmsNotLinked() {
        McpPrivateToolResponse<AssignmentsResponse> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LMS", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<AssignmentsResponse>buildAuthRequired(SESSION_ID, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<AssignmentsResponse> resp = tool.getMyAssignments(SESSION_ID);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(assignmentsService, never()).fetchAssignments(any());
    }

    @Test
    void returnsOkWithDataWhenLinked() {
        AssignmentsResponse stub = new AssignmentsResponse(0L, List.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of("20221528"));
        when(assignmentsService.fetchAssignments("20221528")).thenReturn(stub);

        McpPrivateToolResponse<AssignmentsResponse> resp = tool.getMyAssignments(SESSION_ID);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isSameAs(stub);
        verify(assignmentsService).fetchAssignments("20221528");
    }

    @Test
    void responseDoesNotContainStudentId() {
        AssignmentsResponse stub = new AssignmentsResponse(0L, List.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of("20221528"));
        when(assignmentsService.fetchAssignments("20221528")).thenReturn(stub);

        McpPrivateToolResponse<AssignmentsResponse> resp = tool.getMyAssignments(SESSION_ID);

        assertThat(resp.toString()).doesNotContain("20221528");
        assertThat(resp.provider()).isNull();
    }
}
