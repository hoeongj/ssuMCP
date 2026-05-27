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
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.domain.library.service.LibraryLoansService;

class LibraryLoansMcpToolTests {

    private McpAuthHelper authHelper;
    private LibraryLoansService loansService;
    private LibraryLoansMcpTool tool;

    private static final String SESSION_ID = "test-session-library";
    private static final String OPAQUE_KEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    @BeforeEach
    void setUp() {
        authHelper = mock(McpAuthHelper.class);
        loansService = mock(LibraryLoansService.class);
        tool = new LibraryLoansMcpTool(loansService, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<LibraryLoansResponse> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<LibraryLoansResponse>buildAuthRequired(null, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryLoansResponse> resp = tool.getMyLibraryLoans(null);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(resp.provider()).isEqualTo("LIBRARY");
        assertThat(resp.data()).isNull();
        verify(loansService, never()).getLoansForSession(any());
    }

    @Test
    void returnsAuthRequiredWhenLibraryNotLinked() {
        McpPrivateToolResponse<LibraryLoansResponse> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<LibraryLoansResponse>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryLoansResponse> resp = tool.getMyLibraryLoans(SESSION_ID);

        assertThat(resp.status()).isEqualTo("AUTH_REQUIRED");
        verify(loansService, never()).getLoansForSession(any());
    }

    @Test
    void returnsOkWithDataWhenLinked() {
        LibraryLoansResponse stub = new LibraryLoansResponse(0, List.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(loansService.getLoansForSession(OPAQUE_KEY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryLoansResponse> resp = tool.getMyLibraryLoans(SESSION_ID);

        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.data()).isSameAs(stub);
        // The opaque key (not a student id) is passed to the service
        verify(loansService).getLoansForSession(OPAQUE_KEY);
    }

    @Test
    void usesOpaqueKeyNotStudentId() {
        // LIBRARY principalKey is an opaque UUID, not a studentId
        LibraryLoansResponse stub = new LibraryLoansResponse(2, List.of());
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(loansService.getLoansForSession(OPAQUE_KEY)).thenReturn(stub);

        McpPrivateToolResponse<LibraryLoansResponse> resp = tool.getMyLibraryLoans(SESSION_ID);

        assertThat(resp.status()).isEqualTo("OK");
        verify(loansService).getLoansForSession(OPAQUE_KEY);
        // The opaque key should not appear in the response at all
        assertThat(resp.toString()).doesNotContain(OPAQUE_KEY);
    }
}
