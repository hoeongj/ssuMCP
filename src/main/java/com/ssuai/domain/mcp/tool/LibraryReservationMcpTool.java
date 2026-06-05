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
import com.ssuai.domain.library.reservation.LibraryReservationRequest;

@Component
public class LibraryReservationMcpTool {

    static final String ACTION_TYPE = "LIBRARY_SEAT_RESERVATION";

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationMcpTool.class);

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final McpAuthHelper authHelper;

    public LibraryReservationMcpTool(
            ActionService actionService,
            LibrarySessionStore sessionStore,
            McpAuthHelper authHelper) {
        this.actionService = actionService;
        this.sessionStore = sessionStore;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "prepare_reserve_library_seat",
            description = "숭실대학교 중앙도서관 좌석 예약을 준비합니다. "
                    + "예약은 이 도구만으로 실행되지 않으며, 사용자가 confirm_action을 호출해야 최종 실행됩니다. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth. "
                    + "floor must be one of 2F, 5F, 6F."
    )
    public McpPrivateToolResponse<String> prepareReserveLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id,
            @ToolParam(description = "예약할 도서관 층. 가능한 값: 2F, 5F, 6F.")
            String floor,
            @ToolParam(description = "예약할 좌석 번호.")
            String seat_id
    ) {
        LibraryReservationRequest request = new LibraryReservationRequest(
                normalizeFloor(floor), normalizeSeatId(seat_id));
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> prepareForSession(mcp_session_id, sessionKey, request))
                .orElseGet(() -> {
                    log.debug("prepare_reserve_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> prepareForSession(
            String mcpSessionId, String sessionKey, LibraryReservationRequest request) {
        if (sessionStore.token(sessionKey).isEmpty()) {
            log.debug("prepare_reserve_library_seat: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        actionService.createPendingAction(sessionKey, ACTION_TYPE, request);
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                request.floor() + " " + request.seatId() + "번 좌석 예약을 준비했습니다. "
                        + "confirm_action을 호출해 최종 확인하세요.");
    }

    private static String normalizeFloor(String floor) {
        if (floor == null || floor.isBlank()) {
            throw new IllegalArgumentException("floor is required. 가능한 값: 2F, 5F, 6F.");
        }
        String normalized = floor.trim().toUpperCase();
        if (!normalized.equals("2F") && !normalized.equals("5F") && !normalized.equals("6F")) {
            throw new IllegalArgumentException(
                    "floor: 지원하지 않는 도서관 층입니다. 가능한 값: 2F, 5F, 6F. 받은 값: " + floor + ".");
        }
        return normalized;
    }

    private static String normalizeSeatId(String seatId) {
        if (seatId == null || seatId.isBlank()) {
            throw new IllegalArgumentException("seat_id is required.");
        }
        return seatId.trim();
    }
}
