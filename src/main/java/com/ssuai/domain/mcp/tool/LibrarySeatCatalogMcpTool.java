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
            description = "Returns the static Soongsil library room and seat-map catalog built from screenshots. "
                    + "This does not include live availability and does not require authentication. "
                    + "Use include_layout=true when the LLM needs a compact text-based floor map."
    )
    public LibrarySeatRoomCatalogResponse getLibrarySeatCatalog(
            @ToolParam(description = "Optional floor filter: B1, 2F, 5F, 6F, or numeric 2/5/6.", required = false)
            String floor_code,
            @ToolParam(description = "Optional roomCode filter, such as open-reading-2f.", required = false)
            String room_code,
            @ToolParam(description = "Whether to include compact textLayout lines. Default false.", required = false)
            Boolean include_layout
    ) {
        // No public "debug" param: it exposed internal captureNotes (backend name,
        // data-collection TODOs/screenshot method) to any caller. captureNotes stay
        // reachable only via the service's 4-arg overload for internal/test use
        // (security follow-up #14).
        return roomCatalogService.catalog(floor_code, room_code, include_layout);
    }
}
