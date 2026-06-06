package com.ssuai.domain.mcp.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryCancelRequest;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

@Component
public class ConfirmActionMcpTool {

    private static final Logger log = LoggerFactory.getLogger(ConfirmActionMcpTool.class);

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final McpAuthHelper authHelper;

    public ConfirmActionMcpTool(
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
            name = "confirm_action",
            description = "가장 최근에 준비된 사용자 승인 대기 액션을 최종 확인하고 실행합니다. "
                    + "prepare_reserve_library_seat(좌석 예약) 또는 prepare_cancel_library_seat(좌석 반납)으로 준비한 액션을 실행합니다. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth."
    )
    public McpPrivateToolResponse<String> confirmAction(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> confirmForSession(mcp_session_id, sessionKey))
                .orElseGet(() -> {
                    log.debug("confirm_action: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> confirmForSession(String mcpSessionId, String sessionKey) {
        ActionAudit pending = actionService.findPendingAction(sessionKey).orElse(null);
        if (pending == null) {
            return McpPrivateToolResponse.ok(mcpSessionId, "대기 중인 액션이 없습니다.");
        }
        if (actionService.isExpired(pending)) {
            expirePending(sessionKey);
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "액션이 만료됐습니다. 다시 prepare 도구를 호출하세요.");
        }

        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("confirm_action: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        String actionType = pending.getActionType();
        try {
            if (LibraryReservationMcpTool.ACTION_TYPE.equals(actionType)) {
                return executeReservation(mcpSessionId, sessionKey, token);
            } else if (LibraryCancelMcpTool.ACTION_TYPE.equals(actionType)) {
                return executeCancellation(mcpSessionId, sessionKey, token);
            } else if (LibrarySwapMcpTool.ACTION_TYPE.equals(actionType)) {
                return executeSwap(mcpSessionId, sessionKey, token);
            } else {
                return McpPrivateToolResponse.ok(mcpSessionId, "지원하지 않는 대기 액션입니다.");
            }
        } catch (ActionService.NoPendingActionException exception) {
            return McpPrivateToolResponse.ok(mcpSessionId, "대기 중인 액션이 없습니다.");
        } catch (ActionService.ActionExpiredException exception) {
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "액션이 만료됐습니다. 다시 prepare 도구를 호출하세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action: action failed: type={}", actionType, exception);
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "실행 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private McpPrivateToolResponse<String> executeReservation(
            String mcpSessionId, String sessionKey, String token) {
        ActionAudit confirmed = actionService.confirmAction(sessionKey);
        LibraryReservationRequest request = actionService.payload(confirmed, LibraryReservationRequest.class);
        try {
            LibraryReservationResult result = reservationConnector.reserve(token, request);
            String message = String.format(
                    "%s %s번 좌석 예약 완료! 이용시간: %s ~ %s (예약번호: %d, 반납 시 필요)",
                    result.roomName(), result.seatCode(),
                    result.beginTime(), result.endTime(),
                    result.chargeId());
            return McpPrivateToolResponse.ok(mcpSessionId, message);
        } catch (LibrarySeatNotAvailableException e) {
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "좌석이 이미 선점됐습니다. recommend_library_seats로 다른 좌석을 추천받아 다시 시도해주세요.");
        }
    }

    private McpPrivateToolResponse<String> executeCancellation(
            String mcpSessionId, String sessionKey, String token) {
        ActionAudit confirmed = actionService.confirmAction(sessionKey);
        LibraryCancelRequest request = actionService.payload(confirmed, LibraryCancelRequest.class);
        reservationConnector.discharge(token, request.chargeId());
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                "예약 번호 " + request.chargeId() + " 좌석 반납 완료.");
    }

    private McpPrivateToolResponse<String> executeSwap(
            String mcpSessionId, String sessionKey, String token) {
        ActionAudit confirmed = actionService.confirmAction(sessionKey);
        LibrarySwapRequest request = actionService.payload(confirmed, LibrarySwapRequest.class);
        reservationConnector.discharge(token, request.oldChargeId());
        try {
            LibraryReservationResult result = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.newSeatId()));
            String message = String.format(
                    "자리 변경 완료! %s %s번 예약 완료. 이용시간: %s ~ %s (예약번호: %d, 반납 시 필요)",
                    result.roomName(), result.seatCode(),
                    result.beginTime(), result.endTime(),
                    result.chargeId());
            return McpPrivateToolResponse.ok(mcpSessionId, message);
        } catch (LibrarySeatNotAvailableException exception) {
            log.warn("confirm_action swap: discharge succeeded but seat not available seat={}", request.newSeatId());
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "기존 좌석은 반납됐으나 새 좌석(" + request.newSeatId() + "번)이 이미 선점됐습니다. "
                            + "recommend_library_seats로 다른 좌석을 추천받아 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action swap: discharge succeeded but reserve failed seat={}", request.newSeatId(), exception);
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "기존 좌석은 반납됐으나 새 좌석(" + request.newSeatId() + "번) 예약에 실패했습니다. "
                            + "prepare_reserve_library_seat로 다시 시도해주세요.");
        }
    }

    private void expirePending(String sessionKey) {
        try {
            actionService.confirmAction(sessionKey);
        } catch (ActionService.ActionExpiredException ignored) {
            // confirmAction marks the stale pending action as EXPIRED before throwing.
        }
    }
}
