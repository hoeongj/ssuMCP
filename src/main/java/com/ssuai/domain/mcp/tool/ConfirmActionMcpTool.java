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
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;

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
                    + "현재는 prepare_reserve_library_seat로 준비한 도서관 좌석 예약을 실행합니다. "
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
            return McpPrivateToolResponse.ok(mcpSessionId, "대기 중인 예약이 없습니다.");
        }
        if (!LibraryReservationMcpTool.ACTION_TYPE.equals(pending.getActionType())) {
            return McpPrivateToolResponse.ok(mcpSessionId, "지원하지 않는 대기 액션입니다.");
        }
        if (actionService.isExpired(pending)) {
            expirePending(sessionKey);
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "예약이 만료됐습니다. 다시 prepare_reserve_library_seat를 호출하세요.");
        }

        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("confirm_action: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        try {
            ActionAudit confirmed = actionService.confirmAction(sessionKey);
            LibraryReservationRequest request = actionService.payload(confirmed, LibraryReservationRequest.class);
            reservationConnector.reserve(token, request);
            return McpPrivateToolResponse.ok(
                    mcpSessionId,
                    request.floor() + " " + request.seatId() + "번 좌석 예약을 완료했습니다.");
        } catch (ActionService.NoPendingActionException exception) {
            return McpPrivateToolResponse.ok(mcpSessionId, "대기 중인 예약이 없습니다.");
        } catch (ActionService.ActionExpiredException exception) {
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "예약이 만료됐습니다. 다시 prepare_reserve_library_seat를 호출하세요.");
        } catch (UnsupportedOperationException exception) {
            log.info("confirm_action: reservation connector not implemented");
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "예약 실행 기능이 아직 준비되지 않았습니다: " + exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("confirm_action: reservation failed", exception);
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "예약 실행 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
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
