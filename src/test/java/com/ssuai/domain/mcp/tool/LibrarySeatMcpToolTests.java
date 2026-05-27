package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;

class LibrarySeatMcpToolTests {

    @Test
    void returnsServiceResponseForValidFloor() {
        LibrarySeatService service = mock(LibrarySeatService.class);
        LibrarySeatStatusResponse stub = new LibrarySeatStatusResponse(
                2, "2층", 344, 230, 112, 2,
                Instant.parse("2026-05-15T10:00:00Z"),
                List.of(new LibrarySeatZone("숭실스퀘어ON(2F)", 112, 87, List.of(), List.of()))
        );
        when(service.getSeatStatus(LibraryFloor.F2)).thenReturn(stub);
        LibrarySeatMcpTool tool = new LibrarySeatMcpTool(service);

        LibrarySeatStatusResponse response = tool.getLibrarySeatStatus(2);

        assertThat(response).isSameAs(stub);
    }

    @Test
    void rejectsUnsupportedFloorWithIllegalArgument() {
        LibrarySeatService service = mock(LibrarySeatService.class);
        LibrarySeatMcpTool tool = new LibrarySeatMcpTool(service);

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floor");
    }

    @Test
    void connectorTimeoutMapsToFriendlyKoreanMessage() {
        LibrarySeatService service = mock(LibrarySeatService.class);
        when(service.getSeatStatus(LibraryFloor.F2)).thenThrow(new ConnectorTimeoutException());
        LibrarySeatMcpTool tool = new LibrarySeatMcpTool(service);

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답이 지연");
    }

    @Test
    void connectorParseErrorMapsToFriendlyKoreanMessage() {
        LibrarySeatService service = mock(LibrarySeatService.class);
        when(service.getSeatStatus(LibraryFloor.F2)).thenThrow(new ConnectorParseException());
        LibrarySeatMcpTool tool = new LibrarySeatMcpTool(service);

        assertThatThrownBy(() -> tool.getLibrarySeatStatus(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("도서관 좌석")
                .hasMessageContaining("응답 구조가 변경");
    }
}
