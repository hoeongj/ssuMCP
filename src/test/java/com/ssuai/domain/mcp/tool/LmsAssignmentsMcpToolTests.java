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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.AssignmentItem;
import com.ssuai.domain.lms.dto.AssignmentsCompactResponse;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.service.LmsAssignmentsService;

class LmsAssignmentsMcpToolTests {

    private McpAuthHelper authHelper;
    private LmsAssignmentsService assignmentsService;
    private LmsAssignmentsMcpTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyAssignments(null, null, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(resp.provider()).isEqualTo("LMS");
        assertThat(resp.data()).isNull();
        verify(assignmentsService, never()).fetchAssignments(any(), any());
    }

    @Test
    void returnsAuthRequiredWhenLmsNotLinked() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(SESSION_ID, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyAssignments(SESSION_ID, null, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(assignmentsService, never()).fetchAssignments(any(), any());
    }

    @Test
    void returnsOkWithDataWhenLinked() {
        AssignmentsResponse stub = new AssignmentsResponse(0L, List.of());
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(assignmentsService.fetchAssignments("20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyAssignments(SESSION_ID, null, null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isSameAs(stub);
        verify(assignmentsService).fetchAssignments("20221528", null);
    }

    @Test
    void responseDoesNotContainStudentId() {
        AssignmentsResponse stub = new AssignmentsResponse(0L, List.of());
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(assignmentsService.fetchAssignments("20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyAssignments(SESSION_ID, null, null);

        assertThat(resp.toString()).doesNotContain("20221528");
        assertThat(resp.provider()).isEqualTo("LMS");
    }

    @Test
    void compact_false_returnsFullFields() {
        AssignmentsResponse stub = assignments();
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(assignmentsService.fetchAssignments("20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyAssignments(SESSION_ID, false, null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isInstanceOf(AssignmentsResponse.class);
        AssignmentsResponse data = (AssignmentsResponse) resp.data();
        assertThat(data.termId()).isEqualTo(202601L);
        assertThat(data.items()).hasSize(1);
        assertThat(data.items().get(0).courseName()).isEqualTo("Software Engineering");
        assertThat(data.items().get(0).title()).isEqualTo("Homework 1");
        assertThat(data.items().get(0).type()).isEqualTo("assignment");
        assertThat(data.items().get(0).dueDate()).isEqualTo("2026-06-20T23:59:00+09:00");
    }

    @Test
    void compact_true_returnsOnlySummaryFields() throws Exception {
        AssignmentsResponse stub = assignments();
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(assignmentsService.fetchAssignments("20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyAssignments(SESSION_ID, true, null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isInstanceOf(AssignmentsCompactResponse.class);
        AssignmentsCompactResponse data = (AssignmentsCompactResponse) resp.data();
        assertThat(data.items()).hasSize(1);
        assertThat(data.items().get(0).title()).isEqualTo("Homework 1");
        assertThat(data.items().get(0).dueDate()).isEqualTo("2026-06-20T23:59:00+09:00");

        String json = objectMapper.writeValueAsString(data);
        assertThat(json)
                .contains("\"title\":\"Homework 1\"")
                .contains("\"dueDate\":\"2026-06-20T23:59:00+09:00\"")
                .doesNotContain("termId")
                .doesNotContain("message")
                .doesNotContain("courseName")
                .doesNotContain("type");
    }

    private static AssignmentsResponse assignments() {
        return new AssignmentsResponse(202601L, List.of(
                new AssignmentItem(
                        "Software Engineering",
                        "Homework 1",
                        "assignment",
                        "2026-06-20T23:59:00+09:00"
                )
        ));
    }
}
