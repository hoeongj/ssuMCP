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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusCompactResponse;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(LibrarySeatService.class);
        authHelper = mock(McpAuthHelper.class);
        tool = new LibrarySeatMcpTool(service, authHelper);
    }

    @Test
    void returnsAuthRequiredWhenNoSession() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(null, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(null, McpProviderType.LIBRARY)).thenReturn(Optional.empty());
        when(authHelper.<Object>buildAuthRequired(null, McpProviderType.LIBRARY))
                .thenReturn(stub);

        McpPrivateToolResponse<Object> response =
                tool.getLibrarySeatStatus(2, null, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(response.provider()).isEqualTo("LIBRARY");
        verify(service, never()).getSeatStatusForSession(any(), any());
    }

    @Test
    void returnsServiceResponseForLinkedLibrarySession() {
        LibrarySeatStatusResponse stub = seatStatus();
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(OPAQUE_KEY, SESSION_ID)));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY)).thenReturn(stub);

        McpPrivateToolResponse<Object> response =
                tool.getLibrarySeatStatus(2, SESSION_ID, null);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isSameAs(stub);
        verify(service).getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY);
    }

    @Test
    void expiredLibraryTokenReturnsAuthRequiredForRelinking() {
        McpPrivateToolResponse<Object> stub =
                McpPrivateToolResponse.authRequired(SESSION_ID, "LIBRARY", "https://login.url", EXPIRES);
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(OPAQUE_KEY, SESSION_ID)));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY))
                .thenThrow(new LibraryAuthRequiredException());
        when(authHelper.<Object>buildAuthRequired(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(stub);

        McpPrivateToolResponse<Object> response =
                tool.getLibrarySeatStatus(2, SESSION_ID, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void rejectsUnsupportedFloorBeforeAuthentication() {
        assertThatThrownBy(() -> tool.getLibrarySeatStatus(4, SESSION_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
        verify(authHelper, never()).principalKey(any(), any());
    }

    @Test
    void connectorTimeoutMapsToFriendlyKoreanMessage() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(OPAQUE_KEY, SESSION_ID)));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY))
                .thenThrow(new ConnectorTimeoutException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, SESSION_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답이 지연");
    }

    @Test
    void connectorParseErrorMapsToFriendlyKoreanMessage() {
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(OPAQUE_KEY, SESSION_ID)));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY))
                .thenThrow(new ConnectorParseException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, SESSION_ID, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답 구조가 변경");
    }

    @Test
    void compact_false_returnsFullFields() {
        LibrarySeatStatusResponse stub = seatStatus();
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(OPAQUE_KEY, SESSION_ID)));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY)).thenReturn(stub);

        McpPrivateToolResponse<Object> response =
                tool.getLibrarySeatStatus(2, SESSION_ID, false);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isInstanceOf(LibrarySeatStatusResponse.class);
        LibrarySeatStatusResponse data = (LibrarySeatStatusResponse) response.data();
        assertThat(data.floor()).isEqualTo(2);
        assertThat(data.totalSeats()).isEqualTo(344);
        assertThat(data.availableSeats()).isEqualTo(230);
        assertThat(data.reservedSeats()).isEqualTo(112);
        assertThat(data.outOfServiceSeats()).isEqualTo(2);
        assertThat(data.fetchedAt()).isNotNull();
        assertThat(data.zones()).hasSize(1);
    }

    @Test
    void compact_true_returnsOnlySummaryFields() throws Exception {
        LibrarySeatStatusResponse stub = seatStatus();
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.LIBRARY))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(OPAQUE_KEY, SESSION_ID)));
        when(service.getSeatStatusForSession(LibraryFloor.F2, OPAQUE_KEY)).thenReturn(stub);

        McpPrivateToolResponse<Object> response =
                tool.getLibrarySeatStatus(2, SESSION_ID, true);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data()).isInstanceOf(LibrarySeatStatusCompactResponse.class);
        LibrarySeatStatusCompactResponse data = (LibrarySeatStatusCompactResponse) response.data();
        assertThat(data.floor()).isEqualTo(2);
        assertThat(data.totalSeats()).isEqualTo(344);
        assertThat(data.availableSeats()).isEqualTo(230);
        assertThat(data.occupiedSeats()).isEqualTo(112);

        String json = objectMapper.writeValueAsString(data);
        assertThat(json)
                .contains("\"floor\":2")
                .contains("\"totalSeats\":344")
                .contains("\"availableSeats\":230")
                .contains("\"occupiedSeats\":112")
                .doesNotContain("floorLabel")
                .doesNotContain("reservedSeats")
                .doesNotContain("outOfServiceSeats")
                .doesNotContain("fetchedAt")
                .doesNotContain("zones");
    }

    private static LibrarySeatStatusResponse seatStatus() {
        return new LibrarySeatStatusResponse(
                2, "2층", 344, 230, 112, 2,
                Instant.parse("2026-05-15T10:00:00Z"),
                List.of(new LibrarySeatZone("숭실스퀘어ON(2F)", 112, 87, List.of(), List.of())));
    }
}
