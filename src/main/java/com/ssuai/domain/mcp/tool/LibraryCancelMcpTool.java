package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryCancelRequest;
import com.ssuai.domain.library.reservation.LibraryPrepareResult;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationResult;

@Component
public class LibraryCancelMcpTool {

    public static final String ACTION_TYPE = "LIBRARY_SEAT_CANCEL";

    private static final Logger log = LoggerFactory.getLogger(LibraryCancelMcpTool.class);

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final McpAuthHelper authHelper;

    public LibraryCancelMcpTool(
            ActionService actionService,
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector,
            McpAuthHelper authHelper) {
        this.actionService = actionService;
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "prepare_cancel_library_seat",
            description = "숭실대학교 중앙도서관 현재 예약된 좌석을 반납합니다. "
                    + "현재 예약 정보를 자동으로 조회하므로 예약 번호를 따로 입력하지 않아도 됩니다. "
                    + "반납은 이 도구만으로 실행되지 않으며, confirm_action을 호출해야 최종 실행됩니다. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth."
    )
    public McpPrivateToolResponse<LibraryPrepareResult> prepareCancelLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> prepareForSession(principal.sessionId(), principal.studentId()))
                .orElseGet(() -> {
                    log.debug("prepare_cancel_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<LibraryPrepareResult>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<LibraryPrepareResult> prepareForSession(String mcpSessionId, String sessionKey) {
        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("prepare_cancel_library_seat: library token missing, returning AUTH_REQUIRED");
            return authHelper.<LibraryPrepareResult>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        LibraryReservationResult current = reservationConnector.getCurrentCharge(token).orElse(null);
        if (current == null) {
            return McpPrivateToolResponse.ok(
                    mcpSessionId, McpProviderType.LIBRARY.name(),
                    new LibraryPrepareResult(0L, "현재 예약된 좌석이 없습니다."));
        }

        long actionId = actionService.createPendingAction(
                sessionKey,
                ACTION_TYPE,
                new LibraryCancelRequest(current.chargeId(), current.roomId(), current.seatId())).getId();
        String message = String.format(
                "%s %s번 좌석 반납을 준비했습니다 (예약번호: %d, 이용시간: %s~%s). "
                        + "confirm_action을 호출해 최종 확인하세요.",
                current.roomName(), current.seatCode(), current.chargeId(),
                current.beginTime(), current.endTime());
        return McpPrivateToolResponse.ok(
                mcpSessionId, McpProviderType.LIBRARY.name(), new LibraryPrepareResult(actionId, message));
    }
}
