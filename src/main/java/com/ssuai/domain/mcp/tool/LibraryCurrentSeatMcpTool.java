package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
public class LibraryCurrentSeatMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibraryCurrentSeatMcpTool.class);

    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final McpAuthHelper authHelper;

    public LibraryCurrentSeatMcpTool(
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector,
            McpAuthHelper authHelper) {
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "get_my_library_seat",
            description = "현재 도서관에서 예약 중인 좌석 정보를 조회합니다. "
                    + "예약 중인 좌석이 없으면 없다고 알려줍니다. "
                    + "반납하려면 prepare_cancel_library_seat를, "
                    + "자리를 바꾸려면 prepare_swap_library_seat를 사용하세요. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth."
    )
    public McpPrivateToolResponse<String> getMyLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> fetchForSession(mcp_session_id, sessionKey))
                .orElseGet(() -> {
                    log.debug("get_my_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> fetchForSession(String mcpSessionId, String sessionKey) {
        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("get_my_library_seat: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        try {
            LibraryReservationResult current = reservationConnector.getCurrentCharge(token).orElse(null);
            if (current == null) {
                return McpPrivateToolResponse.ok(mcpSessionId, "현재 예약된 좌석이 없습니다.");
            }
            return McpPrivateToolResponse.ok(mcpSessionId, String.format(
                    "현재 %s %s번 좌석 이용 중. 이용시간: %s ~ %s (예약번호: %d)",
                    current.roomName(), current.seatCode(),
                    current.beginTime(), current.endTime(),
                    current.chargeId()));
        } catch (LibraryAuthRequiredException exception) {
            log.debug("get_my_library_seat: library token expired, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }
    }
}
