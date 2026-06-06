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

@Component
public class LibraryCancelMcpTool {

    static final String ACTION_TYPE = "LIBRARY_SEAT_CANCEL";

    private static final Logger log = LoggerFactory.getLogger(LibraryCancelMcpTool.class);

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final McpAuthHelper authHelper;

    public LibraryCancelMcpTool(
            ActionService actionService,
            LibrarySessionStore sessionStore,
            McpAuthHelper authHelper) {
        this.actionService = actionService;
        this.sessionStore = sessionStore;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "prepare_cancel_library_seat",
            description = "숭실대학교 중앙도서관 좌석 예약을 취소(반납)합니다. "
                    + "취소는 이 도구만으로 실행되지 않으며, 사용자가 confirm_action을 호출해야 최종 실행됩니다. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth. "
                    + "charge_id는 confirm_action(예약 완료) 결과 메시지에서 확인할 수 있습니다."
    )
    public McpPrivateToolResponse<String> prepareCancelLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id,
            @ToolParam(description = "반납할 예약 번호 (chargeId). 예약 완료 시 confirm_action 결과에서 확인.")
            String charge_id
    ) {
        long chargeId = parseChargeId(charge_id);
        LibraryCancelRequest request = new LibraryCancelRequest(chargeId);
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> prepareForSession(mcp_session_id, sessionKey, request))
                .orElseGet(() -> {
                    log.debug("prepare_cancel_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> prepareForSession(
            String mcpSessionId, String sessionKey, LibraryCancelRequest request) {
        if (sessionStore.token(sessionKey).isEmpty()) {
            log.debug("prepare_cancel_library_seat: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        actionService.createPendingAction(sessionKey, ACTION_TYPE, request);
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                "예약 번호 " + request.chargeId() + " 좌석 반납을 준비했습니다. "
                        + "confirm_action을 호출해 최종 확인하세요.");
    }

    private static long parseChargeId(String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            throw new IllegalArgumentException("charge_id is required.");
        }
        try {
            return Long.parseLong(chargeId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("charge_id must be a number. 받은 값: " + chargeId);
        }
    }
}
