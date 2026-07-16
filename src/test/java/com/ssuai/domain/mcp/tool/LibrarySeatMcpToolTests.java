package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusCompactResponse;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibrarySeatMcpToolTests {

    private LibrarySeatService service;
    private LibrarySeatMcpTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(LibrarySeatService.class);
        tool = new LibrarySeatMcpTool(service);
    }

    @Test
    void returnsPublicServiceResponseWithoutSession() {
        LibrarySeatStatusResponse stub = seatStatus();
        when(service.getPublicSeatStatus(LibraryFloor.F2)).thenReturn(stub);

        Object response = tool.getLibrarySeatStatus(2, null);

        assertThat(response).isSameAs(stub);
        verify(service).getPublicSeatStatus(LibraryFloor.F2);
    }

    @Test
    void unavailableInternalSamplerDoesNotAskUserToLogIn() {
        when(service.getPublicSeatStatus(LibraryFloor.F2))
                .thenThrow(new LibraryAuthRequiredException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("잠시 후 다시 시도")
                .hasMessageNotContaining("로그인");
    }

    @Test
    void rejectsUnsupportedFloorBeforeServiceCall() {
        assertThatThrownBy(() -> tool.getLibrarySeatStatus(4, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
    }

    @Test
    void connectorTimeoutMapsToFriendlyKoreanMessage() {
        when(service.getPublicSeatStatus(LibraryFloor.F2))
                .thenThrow(new ConnectorTimeoutException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답이 지연");
    }

    @Test
    void connectorParseErrorMapsToFriendlyKoreanMessage() {
        when(service.getPublicSeatStatus(LibraryFloor.F2))
                .thenThrow(new ConnectorParseException());

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답 구조가 변경");
    }

    @Test
    void compact_false_returnsFullFields() {
        LibrarySeatStatusResponse stub = seatStatus();
        when(service.getPublicSeatStatus(LibraryFloor.F2)).thenReturn(stub);

        Object response = tool.getLibrarySeatStatus(2, false);

        assertThat(response).isInstanceOf(LibrarySeatStatusResponse.class);
        LibrarySeatStatusResponse data = (LibrarySeatStatusResponse) response;
        assertThat(data.floor()).isEqualTo(2);
        assertThat(data.totalSeats()).isEqualTo(344);
        assertThat(data.availableSeats()).isEqualTo(230);
        assertThat(data.occupiedSeats()).isEqualTo(112);
        assertThat(data.reservedSeats()).isZero();
        assertThat(data.outOfServiceSeats()).isEqualTo(2);
        assertThat(data.fetchedAt()).isNotNull();
        assertThat(data.zones()).hasSize(1);
    }

    @Test
    void compact_true_returnsOnlySummaryFields() throws Exception {
        LibrarySeatStatusResponse stub = seatStatus();
        when(service.getPublicSeatStatus(LibraryFloor.F2)).thenReturn(stub);

        Object response = tool.getLibrarySeatStatus(2, true);

        assertThat(response).isInstanceOf(LibrarySeatStatusCompactResponse.class);
        LibrarySeatStatusCompactResponse data = (LibrarySeatStatusCompactResponse) response;
        assertThat(data.floor()).isEqualTo(2);
        assertThat(data.totalSeats()).isEqualTo(344);
        assertThat(data.availableSeats()).isEqualTo(230);
        assertThat(data.occupiedSeats()).isEqualTo(112);
        assertThat(data.physicalTotalSeats()).isEqualTo(344);
        assertThat(data.activeSeats()).isEqualTo(342);
        assertThat(data.inactiveSeats()).isEqualTo(2);

        String json = objectMapper.writeValueAsString(data);
        assertThat(json)
                .contains("\"floor\":2")
                .contains("\"totalSeats\":344")
                .contains("\"availableSeats\":230")
                .contains("\"occupiedSeats\":112")
                .contains("\"reservedSeats\":0")
                .contains("\"outOfServiceSeats\":2")
                .doesNotContain("floorLabel")
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
