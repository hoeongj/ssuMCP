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
import com.ssuai.domain.library.recommendation.LibrarySeatCatalogService;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;

@Component
public class LibrarySwapMcpTool {

    static final String ACTION_TYPE = "LIBRARY_SEAT_SWAP";

    private static final Logger log = LoggerFactory.getLogger(LibrarySwapMcpTool.class);

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final LibrarySeatCatalogService catalogService;
    private final McpAuthHelper authHelper;

    public LibrarySwapMcpTool(
            ActionService actionService,
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector,
            LibrarySeatCatalogService catalogService,
            McpAuthHelper authHelper) {
        this.actionService = actionService;
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
        this.catalogService = catalogService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "prepare_swap_library_seat",
            description = "현재 예약된 도서관 좌석을 자동으로 반납하고 새 좌석으로 변경합니다. "
                    + "현재 예약 정보를 자동으로 조회하므로 charge_id를 따로 입력하지 않아도 됩니다. "
                    + "변경은 이 도구만으로 실행되지 않으며, confirm_action을 호출해야 최종 실행됩니다. "
                    + "현재 예약이 없으면 prepare_reserve_library_seat를 사용하세요. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth."
    )
    public McpPrivateToolResponse<String> prepareSwapLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id,
            @ToolParam(description = "새로 예약할 좌석 ID (숫자). get_library_seat_status 또는 recommend_library_seats에서 확인.")
            String new_seat_id
    ) {
        long newSeatId = parseSeatId(new_seat_id);
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> prepareForSession(mcp_session_id, sessionKey, newSeatId))
                .orElseGet(() -> {
                    log.debug("prepare_swap_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> prepareForSession(
            String mcpSessionId, String sessionKey, long newSeatId) {
        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("prepare_swap_library_seat: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        LibraryReservationResult current = reservationConnector.getCurrentCharge(token).orElse(null);
        if (current == null) {
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "현재 예약된 좌석이 없습니다. prepare_reserve_library_seat를 사용하세요.");
        }

        actionService.createPendingAction(sessionKey, ACTION_TYPE, new LibrarySwapRequest(current.chargeId(), newSeatId));
        return McpPrivateToolResponse.ok(mcpSessionId, String.format(
                "현재 %s %s번(예약번호: %d) → 새 %s으로 변경을 준비했습니다. "
                        + "confirm_action을 호출해 최종 확인하세요.%s",
                current.roomName(), current.seatCode(), current.chargeId(),
                SeatDisplay.describe(catalogService, newSeatId),
                SeatDisplay.graduateOnlyWarning(catalogService, newSeatId)));
    }

    private static long parseSeatId(String seatId) {
        if (seatId == null || seatId.isBlank()) {
            throw new IllegalArgumentException("new_seat_id is required.");
        }
        try {
            return Long.parseLong(seatId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("new_seat_id must be a number. 받은 값: " + seatId);
        }
    }
}
