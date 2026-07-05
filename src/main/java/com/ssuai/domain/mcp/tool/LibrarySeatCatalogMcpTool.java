package com.ssuai.domain.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;

@Component
public class LibrarySeatCatalogMcpTool {

    private final LibrarySeatRoomCatalogService roomCatalogService;

    public LibrarySeatCatalogMcpTool(LibrarySeatRoomCatalogService roomCatalogService) {
        this.roomCatalogService = roomCatalogService;
    }

    @Tool(
            name = "get_library_seat_catalog",
            description = "스크린샷으로 구축한 숭실대학교 도서관 열람실·좌석 배치도 정적 카탈로그를 반환합니다. "
                    + "실시간 좌석 현황은 포함하지 않으며 인증도 필요하지 않습니다. "
                    + "LLM이 간단한 텍스트 기반 층별 배치도가 필요할 때는 include_layout=true를 사용하세요. "
                    + "인증 불필요."
    )
    public LibrarySeatRoomCatalogResponse getLibrarySeatCatalog(
            @ToolParam(description = "층 필터(선택): B1, 2F, 5F, 6F 또는 숫자 2/5/6.", required = false)
            String floor_code,
            @ToolParam(description = "roomCode 필터(선택), 예: open-reading-2f.", required = false)
            String room_code,
            @ToolParam(description = "compact textLayout 라인 포함 여부. 기본 false.", required = false)
            Boolean include_layout
    ) {
        // No public "debug" param: it exposed internal captureNotes (backend name,
        // data-collection TODOs/screenshot method) to any caller. captureNotes stay
        // reachable only via the service's 4-arg overload for internal/test use
        // (security follow-up #14).
        return roomCatalogService.catalog(floor_code, room_code, include_layout);
    }
}
