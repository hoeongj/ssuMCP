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
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;

@Component
public class LibraryReservationMcpTool {

    static final String ACTION_TYPE = "LIBRARY_SEAT_RESERVATION";

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationMcpTool.class);

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final McpAuthHelper authHelper;

    public LibraryReservationMcpTool(
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
            name = "prepare_reserve_library_seat",
            description = "숭실대학교 중앙도서관 좌석 예약을 준비합니다. "
                    + "예약은 이 도구만으로 실행되지 않으며, 사용자가 confirm_action을 호출해야 최종 실행됩니다. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth. "
                    + "seat_id는 recommend_library_seats 응답의 externalSeatId 값(숫자 문자열, 예: '3179')을 사용하세요. "
                    + "추천 목록 없이 직접 예약하려면 oasis.ssu.ac.kr에서 좌석을 클릭해 URL의 숫자를 확인하세요."
    )
    public McpPrivateToolResponse<String> prepareReserveLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id,
            @ToolParam(description = "예약할 좌석 ID (숫자). get_library_seat_status 또는 recommend_library_seats에서 확인.")
            String seat_id
    ) {
        long seatId = parseSeatId(seat_id);
        LibraryReservationRequest request = new LibraryReservationRequest(seatId);
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> prepareForSession(mcp_session_id, sessionKey, request))
                .orElseGet(() -> {
                    log.debug("prepare_reserve_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> prepareForSession(
            String mcpSessionId, String sessionKey, LibraryReservationRequest request) {
        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("prepare_reserve_library_seat: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        LibraryReservationResult active = reservationConnector.getCurrentCharge(token).orElse(null);
        if (active != null) {
            return McpPrivateToolResponse.ok(mcpSessionId, String.format(
                    "이미 %s %s번 좌석 예약 중입니다 (예약번호: %d, 이용시간: %s~%s). "
                            + "자리를 바꾸려면 prepare_swap_library_seat를 사용하세요. "
                            + "반납하려면 prepare_cancel_library_seat(charge_id=%d)를 사용하세요.",
                    active.roomName(), active.seatCode(), active.chargeId(),
                    active.beginTime(), active.endTime(), active.chargeId()));
        }

        actionService.createPendingAction(sessionKey, ACTION_TYPE, request);
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                request.seatId() + "번 좌석 예약을 준비했습니다. "
                        + "confirm_action을 호출해 최종 확인하세요.");
    }

    private static long parseSeatId(String seatId) {
        if (seatId == null || seatId.isBlank()) {
            throw new IllegalArgumentException("seat_id is required.");
        }
        try {
            return Long.parseLong(seatId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("seat_id must be a number. 받은 값: " + seatId);
        }
    }
}
