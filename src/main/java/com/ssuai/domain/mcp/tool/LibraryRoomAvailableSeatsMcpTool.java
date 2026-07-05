package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.dto.LibraryRoomAvailableSeatsResponse;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
public class LibraryRoomAvailableSeatsMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibraryRoomAvailableSeatsMcpTool.class);

    private final LibraryAvailableSeatsService availableSeatsService;
    private final McpAuthHelper authHelper;

    public LibraryRoomAvailableSeatsMcpTool(
            LibraryAvailableSeatsService availableSeatsService,
            McpAuthHelper authHelper
    ) {
        this.availableSeatsService = availableSeatsService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_room_available_seats",
            description = "숭실대학교 도서관 특정 열람실의 모든 좌석 현황을 실시간으로 조회합니다. "
                    + "좌석별 상태(available/occupied/away/inactive), 좌석 번호(label), 좌석 유형, "
                    + "이석 시 잔여 시간(remainingTime, 분)을 반환합니다. "
                    + "roomId 가능한 값: 15(1열람실 B1F), 53(숭실스퀘어ON 2F), 54(오픈열람실 2F), "
                    + "57(마루열람실 6F), 58(대학원열람실 6F), 59(리클라이너 5F), 60(숭실멀티라운지 5F). "
                    + "externalSeatId를 prepare_reserve_library_seat에서 사용하세요. "
                    + "mcp_session_id 필요(LIBRARY 로그인)."
    )
    public McpPrivateToolResponse<LibraryRoomAvailableSeatsResponse> getRoomAvailableSeats(
            @ToolParam(description = "열람실 ID. 가능한 값: 15(1열람실 B1F), 53(숭실스퀘어ON), 54(오픈열람실), 57(마루열람실), 58(대학원열람실), 59(리클라이너), 60(숭실멀티라운지).")
            int roomId,
            @ToolParam(description = "start_auth(LIBRARY)로 발급받은 MCP session ID.")
            String mcp_session_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> {
                    log.debug("get_room_available_seats: roomId={}", roomId);
                    try {
                        LibraryRoomAvailableSeatsResponse data =
                                availableSeatsService.getRoomAvailableSeats(roomId, principal.studentId());
                        return McpPrivateToolResponse.<LibraryRoomAvailableSeatsResponse>ok(
                                principal.sessionId(), McpProviderType.LIBRARY.name(), data);
                    } catch (LibraryAuthRequiredException exception) {
                        log.debug("get_room_available_seats: token expired");
                        return authHelper.<LibraryRoomAvailableSeatsResponse>buildAuthRequired(
                                mcp_session_id, McpProviderType.LIBRARY);
                    } catch (ConnectorException exception) {
                        throw new IllegalStateException(
                                ConnectorErrorMessages.forResource("도서관 좌석", exception), exception);
                    }
                })
                .orElseGet(() -> {
                    log.debug("get_room_available_seats: LIBRARY not linked");
                    return authHelper.<LibraryRoomAvailableSeatsResponse>buildAuthRequired(
                            mcp_session_id, McpProviderType.LIBRARY);
                });
    }
}
