package com.ssuai.domain.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.exception.ConnectorException;

@Component
public class LibrarySeatMcpTool {

    private final LibrarySeatService libraryService;

    public LibrarySeatMcpTool(LibrarySeatService libraryService) {
        this.libraryService = libraryService;
    }

    @Tool(
            name = "get_library_seat_status",
            description = "숭실대학교 중앙도서관의 현재 좌석 현황을 층별로 조회합니다. "
                    + "응답에는 해당 층의 전체/이용 가능/예약/사용 불가 좌석 수와 구역별 분포가 포함됩니다. "
                    + "이 도구는 읽기 전용이며, 좌석 예약은 별도의 동작 도구로 분리되어 있습니다."
    )
    public LibrarySeatStatusResponse getLibrarySeatStatus(
            @ToolParam(description = "조회할 도서관 층 코드. 가능한 값: 2 (2층), 5 (5층), 6 (6층).")
            int floor
    ) {
        LibraryFloor target = LibraryFloor.fromCode(floor);
        try {
            return libraryService.getSeatStatus(target);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("도서관 좌석", exception), exception);
        }
    }
}
