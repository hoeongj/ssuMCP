package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusCompactResponse;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.service.LibrarySeatService;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
public class LibrarySeatMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatMcpTool.class);

    private final LibrarySeatService libraryService;

    public LibrarySeatMcpTool(LibrarySeatService libraryService) {
        this.libraryService = libraryService;
    }

    @Tool(
            name = "get_library_seat_status",
            description = "숭실대학교 중앙도서관의 현재 좌석 현황을 층별로 조회합니다. "
                    + "응답에는 해당 층의 전체/이용 가능/예약/사용 불가 좌석 수와 구역별 분포가 포함됩니다. "
                    + "compact=true 지원. "
                    + "좌석 집계는 공개 정보이며 인증이 필요하지 않습니다. "
                    + "이 도구는 읽기 전용이며, 좌석 예약은 별도의 동작 도구로 분리되어 있습니다."
    )
    public Object getLibrarySeatStatus(
            @ToolParam(description = "조회할 도서관 층 코드. 가능한 값: 2 (2층), 5 (5층), 6 (6층).")
            int floor,
            @ToolParam(description = "compact=true: 전체/가용/예약 수치만 반환 (층 요약). compact=false(기본): 구역별 상세 포함.", required = false)
            Boolean compact
    ) {
        LibraryFloor target = LibraryFloor.fromCode(floor);
        boolean isCompact = Boolean.TRUE.equals(compact);
        log.debug("get_library_seat_status: fetching public seat aggregate");
        try {
            LibrarySeatStatusResponse data = libraryService.getPublicSeatStatus(target);
            return isCompact ? LibrarySeatStatusCompactResponse.from(data) : data;
        } catch (LibraryAuthRequiredException exception) {
            throw new IllegalStateException(
                    "도서관 좌석 현황을 지금 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                    exception);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("도서관 좌석", exception), exception);
        }
    }
}
