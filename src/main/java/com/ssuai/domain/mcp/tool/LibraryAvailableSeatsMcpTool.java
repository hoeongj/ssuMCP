package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsResponse;
import com.ssuai.domain.library.service.LibraryAvailableSeatsService;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
public class LibraryAvailableSeatsMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibraryAvailableSeatsMcpTool.class);

    private final LibraryAvailableSeatsService availableSeatsService;
    private final McpAuthHelper authHelper;

    public LibraryAvailableSeatsMcpTool(
            LibraryAvailableSeatsService availableSeatsService,
            McpAuthHelper authHelper
    ) {
        this.availableSeatsService = availableSeatsService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_library_available_seats",
            description = "숭실대학교 도서관 전체 열람실(7개)의 예약 가능 좌석을 실시간으로 조회합니다. "
                    + "열람실별로 이용 가능한 좌석 수, externalSeatId 목록, 좌석 번호 목록을 반환합니다. "
                    + "이석(away) 상태인 좌석(자리 비움, 곧 반납될 수 있음)도 함께 표시됩니다. "
                    + "externalSeatId는 prepare_reserve_library_seat 호출 시 사용합니다. "
                    + "전체 현황 파악 후 특정 열람실 세부 현황은 get_room_available_seats로 조회하세요. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth."
    )
    public McpPrivateToolResponse<LibraryAllAvailableSeatsResponse> getLibraryAvailableSeats(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> {
                    log.debug("get_library_available_seats: fetching all rooms");
                    try {
                        LibraryAllAvailableSeatsResponse data =
                                availableSeatsService.getAllAvailableSeats(sessionKey);
                        return McpPrivateToolResponse.<LibraryAllAvailableSeatsResponse>ok(
                                mcp_session_id, data);
                    } catch (LibraryAuthRequiredException exception) {
                        log.debug("get_library_available_seats: token expired");
                        return authHelper.<LibraryAllAvailableSeatsResponse>buildAuthRequired(
                                mcp_session_id, McpProviderType.LIBRARY);
                    } catch (ConnectorException exception) {
                        throw new IllegalStateException(
                                ConnectorErrorMessages.forResource("도서관 좌석", exception), exception);
                    }
                })
                .orElseGet(() -> {
                    log.debug("get_library_available_seats: LIBRARY not linked");
                    return authHelper.<LibraryAllAvailableSeatsResponse>buildAuthRequired(
                            mcp_session_id, McpProviderType.LIBRARY);
                });
    }
}
