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
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

/**
 * Shared confirm step for library write actions (ADR 0015).
 *
 * <p>Flow: {@code claimPendingAction} moves the latest PENDING action to EXECUTING under a
 * row lock (single-use, concurrency-safe), the upstream call runs, then
 * {@code completeAction} records the terminal outcome. Because the audit row only becomes
 * SUCCESS after the upstream call actually succeeds, it never reports success for a failed
 * reservation.
 */
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
            actionService.expirePending(sessionKey);
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "액션이 만료됐습니다. 다시 prepare 도구를 호출하세요.");
        }

        // Token check before claiming: if the library session is gone the action stays
        // PENDING so the user can re-auth and confirm the same prepared action again.
        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("confirm_action: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        // Claim atomically (PENDING -> EXECUTING under a row lock). A concurrent confirm
        // that already claimed this action leaves nothing PENDING for us.
        ActionAudit claimed;
        try {
            claimed = actionService.claimPendingAction(sessionKey);
        } catch (ActionService.NoPendingActionException exception) {
            return McpPrivateToolResponse.ok(mcpSessionId, "대기 중인 액션이 없습니다.");
        } catch (ActionService.ActionExpiredException exception) {
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "액션이 만료됐습니다. 다시 prepare 도구를 호출하세요.");
        }

        String actionType = claimed.getActionType();
        if (LibraryReservationMcpTool.ACTION_TYPE.equals(actionType)) {
            return executeReservation(mcpSessionId, claimed, token);
        } else if (LibraryCancelMcpTool.ACTION_TYPE.equals(actionType)) {
            return executeCancellation(mcpSessionId, claimed, token);
        } else if (LibrarySwapMcpTool.ACTION_TYPE.equals(actionType)) {
            return executeSwap(mcpSessionId, claimed, token);
        }
        actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "지원하지 않는 액션 타입");
        return McpPrivateToolResponse.ok(mcpSessionId, "지원하지 않는 대기 액션입니다.");
    }

    private McpPrivateToolResponse<String> executeReservation(
            String mcpSessionId, ActionAudit claimed, String token) {
        LibraryReservationRequest request = actionService.payload(claimed, LibraryReservationRequest.class);
        try {
            LibraryReservationResult result = reservationConnector.reserve(token, request);
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    result.roomName() + " " + result.seatCode() + "번 예약 완료");
            return McpPrivateToolResponse.ok(mcpSessionId, String.format(
                    "%s %s번 좌석 예약 완료! 이용시간: %s ~ %s (예약번호: %d, 반납 시 필요)",
                    result.roomName(), result.seatCode(),
                    result.beginTime(), result.endTime(), result.chargeId()));
        } catch (LibrarySeatNotAvailableException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_RACE, "좌석이 이미 선점됨");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "좌석이 이미 선점됐습니다. recommend_library_seats로 다른 좌석을 추천받아 다시 시도해주세요.");
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "도서관 세션 만료");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "학교 서버 응답 없음");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "학교 서버가 응답이 없어요. 잠시 후 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action reserve failed", exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "예약 실행 오류");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "실행 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private McpPrivateToolResponse<String> executeCancellation(
            String mcpSessionId, ActionAudit claimed, String token) {
        LibraryCancelRequest request = actionService.payload(claimed, LibraryCancelRequest.class);
        try {
            reservationConnector.discharge(token, request.chargeId());
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    "예약 " + request.chargeId() + " 반납 완료");
            return McpPrivateToolResponse.ok(
                    mcpSessionId, "예약 번호 " + request.chargeId() + " 좌석 반납 완료.");
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "도서관 세션 만료");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "학교 서버 응답 없음");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "학교 서버가 응답이 없어요. 잠시 후 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action cancel failed", exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "반납 실행 오류");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "실행 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private McpPrivateToolResponse<String> executeSwap(
            String mcpSessionId, ActionAudit claimed, String token) {
        LibrarySwapRequest request = actionService.payload(claimed, LibrarySwapRequest.class);
        // Step 1 — release the current seat.
        try {
            reservationConnector.discharge(token, request.oldChargeId());
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "도서관 세션 만료");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "학교 서버 응답 없음 (반납 단계)");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "학교 서버가 응답이 없어 자리 변경을 못 했어요. 잠시 후 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action swap: discharge failed seat={}", request.newSeatId(), exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "기존 좌석 반납 실패");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "기존 좌석 반납에 실패해 자리 변경을 못 했어요. 잠시 후 다시 시도해주세요.");
        }
        // Step 2 — reserve the new seat. The old seat is already released.
        try {
            LibraryReservationResult result = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.newSeatId()));
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    result.roomName() + " " + result.seatCode() + "번으로 변경 완료");
            return McpPrivateToolResponse.ok(mcpSessionId, String.format(
                    "자리 변경 완료! %s %s번 예약 완료. 이용시간: %s ~ %s (예약번호: %d, 반납 시 필요)",
                    result.roomName(), result.seatCode(),
                    result.beginTime(), result.endTime(), result.chargeId()));
        } catch (LibrarySeatNotAvailableException exception) {
            log.warn("confirm_action swap: discharge succeeded but seat not available seat={}", request.newSeatId());
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_RACE,
                    "기존 좌석 반납 후 새 좌석 선점됨");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "기존 좌석은 반납됐으나 새 좌석(" + request.newSeatId() + "번)이 이미 선점됐습니다. "
                            + "recommend_library_seats로 다른 좌석을 추천받아 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action swap: discharge succeeded but reserve failed seat={}", request.newSeatId(), exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM,
                    "기존 좌석 반납 후 새 좌석 예약 실패");
            return McpPrivateToolResponse.ok(mcpSessionId,
                    "기존 좌석은 반납됐으나 새 좌석(" + request.newSeatId() + "번) 예약에 실패했습니다. "
                            + "prepare_reserve_library_seat로 다시 시도해주세요.");
        }
    }
}
