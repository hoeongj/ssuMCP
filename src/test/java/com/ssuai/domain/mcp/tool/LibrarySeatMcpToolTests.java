package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibrarySeatMcpToolTests {

    private static final String SESSION_ID = "test-session-library";
    private static final String OPAQUE_KEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final Instant EXPIRES = Instant.parse("2026-05-18T15:00:00Z");

    private LibrarySeatService service;
    private McpAuthHelper authHelper;
    private LibrarySeatMcpTool tool;

    @BeforeEach
    void setUp() {
        service = mock(LibrarySeatService.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibrarySeatMcpTool(service, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<LibrarySeatStatusResponse> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<LibrarySeatStatusResponse>buildAuthRequired(null, McpProviderType.LIBRARY))
                .thenReturn(stub);

        McpPrivateToolResponse<LibrarySeatStatusResponse> response =
                tool.getLibrarySeatStatus(2, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.provider()).isEqualTo("LIBRARY");
        verify(service, never()).getSeatStatusForSession(any(), any());
    }

    @Test
    void returnsServiceResponseForLinkedLibrarySession() {
        LibrarySeatStatusResponse stub = seatStatus();
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY)).thenReturn(stub);

        McpPrivateToolResponse<LibrarySeatStatusResponse> response =
                tool.getLibrarySeatStatus(2, SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isSameAs(stub);
        verify(service).getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY);
    }

    @Test
    void expiredLibraryTokenReturnsAuthRequiredForRelinking() {
        McpPrivateToolResponse<LibrarySeatStatusResponse> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY))
                .thenThrow(new LibraryAuthRequiredException());
        when(authHelper.<LibrarySeatStatusResponse>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(stub);

        McpPrivateToolResponse<LibrarySeatStatusResponse> response =
                tool.getLibrarySeatStatus(2, SESSION_ID);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void rejectsUnsupportedFloorBeforeAuthentication() {
        assertThatThrownBy(() -> tool.getLibrarySeatStatus(4, SESSION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
        verify(authHelper, never()).principalKey(any(), any());
    }

    @Test
    void connectorTimeoutMapsToFriendlyKoreanMessage() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY))
                .thenThrow(new ConnectorTimeoutException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답이 지연");
    }

    @Test
    void connectorParseErrorMapsToFriendlyKoreanMessage() {
        when(authHelper.principalKey(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(OPAQUE_KEY));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY))
                .thenThrow(new ConnectorParseException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답 구조가 변경");
    }

    private static LibrarySeatStatusResponse seatStatus() {
        return new LibrarySeatStatusResponse(
                2, "2층", 344, 230, 112, 2,
                Instant.parse("2026-05-15T10:00:00Z"),
                List.of(new LibrarySeatZone("숭실스퀘어ON(2F)", 112, 87, List.of(), List.of())));
    }
}
