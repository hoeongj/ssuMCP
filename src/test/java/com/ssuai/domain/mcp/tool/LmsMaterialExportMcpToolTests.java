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

import com.ssuai.domain.action.ActionService.ActionExpiredException;
import com.ssuai.domain.action.ActionService.NoPendingActionException;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.lms.dto.LmsExportConfirmResponse;
import com.ssuai.domain.lms.dto.LmsExportPrepareResponse;
import com.ssuai.domain.lms.service.LmsMaterialExportService;
import com.ssuai.global.exception.LmsApiException;
import com.ssuai.global.exception.LmsSessionExpiredException;

class LmsMaterialExportMcpToolTests {

    private McpAuthHelper authHelper;
    private LmsMaterialExportService exportService;
    private LmsMaterialExportMcpTool tool;

    private static final String SESSION_ID = "test-session-lms";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        exportService = mock(LmsMaterialExportService.class);
        tool = new LmsMaterialExportMcpTool(exportService, authHelper);
    }

    @Test
    void prepare_returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.prepareLmsMaterialExport(null, List.of("c1"), null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(exportService, never()).prepareForMcp(any(), any(), any(), any());
    }

    @Test
    void prepare_returnsOkWhenLinked() {
        LmsExportPrepareResponse stub = new LmsExportPrepareResponse(0, 0, 0, List.of(), List.of(), "message");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(exportService.prepareForMcp(SESSION_ID, "20221528", null, List.of("c1"))).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.prepareLmsMaterialExport(SESSION_ID, List.of("c1"), null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isEqualTo(stub);
    }

    @Test
    void prepare_returnsStructuredOutcomeWhenLmsIsUnavailable() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(
                        "20221528", SESSION_ID)));
        when(exportService.prepareForMcp(SESSION_ID, "20221528", null, List.of("c1")))
                .thenThrow(new LmsApiException("private upstream response", 503));

        McpPrivateToolResponse<Object> response =
                tool.prepareLmsMaterialExport(SESSION_ID, List.of("c1"), null);

        assertThat(response.status()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.retryable()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.toString()).doesNotContain("private upstream response");
    }

    @Test
    void confirm_returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.confirmLmsMaterialExport(null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(exportService, never()).confirmForMcp(any(), any(), any());
    }

    @Test
    void confirm_handlesNoPendingActionGracefully() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(exportService.confirmForMcp(SESSION_ID, "20221528", null))
                .thenThrow(new NoPendingActionException());

        McpPrivateToolResponse<Object> resp = tool.confirmLmsMaterialExport(SESSION_ID);

        assertThat(resp.status()).isEqualTo("NO_PENDING_ACTION");
        assertThat(resp.code()).isEqualTo("NO_PENDING_ACTION");
    }

    @Test
    void confirm_handlesActionExpiredGracefully() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(exportService.confirmForMcp(SESSION_ID, "20221528", null))
                .thenThrow(new ActionExpiredException());

        McpPrivateToolResponse<Object> resp = tool.confirmLmsMaterialExport(SESSION_ID);

        assertThat(resp.status()).isEqualTo("NO_PENDING_ACTION");
        assertThat(resp.code()).isEqualTo("NO_PENDING_ACTION");
    }

    @Test
    void confirm_returnsStructuredOutcomeWhenLmsIsUnavailable() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(
                        "20221528", SESSION_ID)));
        when(exportService.confirmForMcp(SESSION_ID, "20221528", null))
                .thenThrow(new LmsApiException("private upstream response", 503));

        McpPrivateToolResponse<Object> response = tool.confirmLmsMaterialExport(SESSION_ID);

        assertThat(response.status()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(response.retryable()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.toString()).doesNotContain("private upstream response");
    }

    @Test
    void confirm_returnsOkWhenSuccessful() {
        LmsExportConfirmResponse stub = new LmsExportConfirmResponse("job1", 1, 100L, "expiry", "url", "");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(exportService.confirmForMcp(SESSION_ID, "20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.confirmLmsMaterialExport(SESSION_ID);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isEqualTo(stub);
    }

    @Test
    void exportAll_returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LMS", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LMS)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LMS)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.exportAllLmsMaterials(null, null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(exportService, never()).exportAllForMcp(any(), any(), any());
    }

    @Test
    void exportAll_returnsOkWhenLinked() {
        LmsExportPrepareResponse stub = new LmsExportPrepareResponse(0, 0, 0, List.of(), List.of(), "message");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(exportService.exportAllForMcp(SESSION_ID, "20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.exportAllLmsMaterials(SESSION_ID, null);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isEqualTo(stub);
    }

    @Test
    void exportAll_returnsStructuredOutcomeWhenLmsProtocolChanges() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(
                        "20221528", SESSION_ID)));
        when(exportService.exportAllForMcp(SESSION_ID, "20221528", null))
                .thenThrow(new LmsApiException("unexpected schema", 400));

        McpPrivateToolResponse<Object> response =
                tool.exportAllLmsMaterials(SESSION_ID, null);

        assertThat(response.status()).isEqualTo("UPSTREAM_PROTOCOL_CHANGED");
        assertThat(response.retryable()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.toString()).doesNotContain("unexpected schema");
    }

    @Test
    void toStringDoesNotLeakStudentId() {
        LmsExportConfirmResponse stub = new LmsExportConfirmResponse("job1", 1, 100L, "expiry", "url", "");
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LMS)).thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal("20221528", SESSION_ID)));
        when(exportService.confirmForMcp(SESSION_ID, "20221528", null)).thenReturn(stub);

        McpPrivateToolResponse<Object> resp = tool.confirmLmsMaterialExport(SESSION_ID);

        assertThat(resp.toString()).doesNotContain("20221528");
    }
}
