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
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsCourseMaterials;
import com.ssuai.domain.lms.dto.LmsMaterialsResponse;
import com.ssuai.domain.lms.dto.LmsTermType;
import com.ssuai.domain.lms.service.LmsMaterialsService;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

class LmsMaterialsMcpToolTests {

    private McpAuthHelper authHelper;
    private LmsMaterialsService materialsService;
    private LmsMaterialsMcpTool tool;

    private static final String SESSION_ID = "test-session-lms";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        materialsService = mock(LmsMaterialsService.class);
        tool = new LmsMaterialsMcpTool(materialsService, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyLmsCourses(null, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(materialsService, never()).listMaterialsWithSelection(any(), any(), any());
    }

    @Test
    void returnsAuthRequiredWhenSessionExpired() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(materialsService.listMaterialsWithSelection("20221528", null, null))
                .thenThrow(new LmsSessionExpiredException());

        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LMS", "https://login.url", EXPIRES);
        when(authHelper.<Object>buildAuthRequired(SESSION_ID, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.getMyLmsCourses(SESSION_ID, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void returnsOkWithCoursesWhenLinked() {
        List<LmsCourseMaterials> stub = List.of(
                new LmsCourseMaterials(new LmsCourse(1L, "Math", "MATH101", 100L), List.of(), 0, 0L));
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        LmsMaterialsResponse result = response(stub);
        when(materialsService.listMaterialsWithSelection("20221528", null, null))
                .thenReturn(result);

        McpPrivateToolResponse<Object> resp = tool.getMyLmsCourses(SESSION_ID, null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isEqualTo(result);
    }

    @Test
    void returnsOkWithMaterialsWhenLinked() {
        List<LmsCourseMaterials> stub = List.of();
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        LmsMaterialsResponse result = response(stub);
        when(materialsService.listMaterialsWithSelection("20221528", List.of(1L), null))
                .thenReturn(result);

        McpPrivateToolResponse<Object> resp = tool.getMyLmsMaterials(SESSION_ID, List.of(1L), null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isEqualTo(result);
    }

    @Test
    void coursesMapsRetryableUpstreamFailureWithoutExposingExceptionDetails() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(
                        "20221528", SESSION_ID)));
        when(materialsService.listMaterialsWithSelection("20221528", null, null))
                .thenThrow(new LmsApiException("private upstream response", 503));

        McpPrivateToolResponse<Object> response = tool.getMyLmsCourses(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.retryable()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.toString()).doesNotContain("private upstream response");
    }

    @Test
    void materialsMapsProtocolFailureAsNonRetryableStructuredOutcome() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(
                        "20221528", SESSION_ID)));
        when(materialsService.listMaterialsWithSelection(
                "20221528", List.of(1L), null))
                .thenThrow(new LmsApiException("unexpected schema", 400));

        McpPrivateToolResponse<Object> response =
                tool.getMyLmsMaterials(SESSION_ID, List.of(1L), null);

        assertThat(response.status()).isEqualTo("UPSTREAM_PROTOCOL_CHANGED");
        assertThat(response.retryable()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.toString()).doesNotContain("unexpected schema");
    }

    @Test
    void toStringDoesNotLeakStudentId() {
        List<LmsCourseMaterials> stub = List.of(
                new LmsCourseMaterials(new LmsCourse(1L, "Math", "MATH101", 100L), List.of(), 0, 0L));
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(materialsService.listMaterialsWithSelection("20221528", null, null))
                .thenReturn(response(stub));

        McpPrivateToolResponse<Object> resp = tool.getMyLmsCourses(SESSION_ID, null);

        assertThat(resp.toString()).doesNotContain("20221528");
    }

    private static LmsMaterialsResponse response(List<LmsCourseMaterials> courses) {
        return new LmsMaterialsResponse(
                courses, 100L, "2026년 1학기", LmsTermType.REGULAR,
                "ACTIVE_REGULAR_TERM", List.of(LmsTermType.REGULAR));
    }
}
