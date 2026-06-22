package com.ssuai.domain.mcp.tool;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.reservation.LibraryCancelRequest;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

/**
 * Shared confirm step for library write actions (ADR 0015).
 *
 * <p>Reserve confirms now create an immediate reservation intent and wait briefly
 * for the queue worker. Cancel/swap still execute directly because PR2 only routes
 * reserve through the intent queue.
 */
@Component
public class ConfirmActionMcpTool {

    private static final Logger log = LoggerFactory.getLogger(ConfirmActionMcpTool.class);
    private static final Duration RESERVATION_INTENT_WAIT = Duration.ofSeconds(8);
    private static final Duration RESERVATION_INTENT_POLL = Duration.ofMillis(200);
    private static final String NOT_AVAILABLE_STATE_CODE = "warning.smuf.notAvailableState";

    private final ActionService actionService;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final LibraryReservationIntentTransactions intentTransactions;
    private final LibrarySeatEventPublisher seatEventPublisher;
    private final McpAuthHelper authHelper;

    public ConfirmActionMcpTool(
            ActionService actionService,
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector,
            LibraryReservationIntentTransactions intentTransactions,
            LibrarySeatEventPublisher seatEventPublisher,
            McpAuthHelper authHelper) {
        this.actionService = actionService;
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
        this.intentTransactions = intentTransactions;
        this.seatEventPublisher = seatEventPublisher;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "confirm_action",
            description = "준비된 사용자 승인 대기 액션을 최종 확인하고 실행합니다. "
                    + "기본적으로 현재 대기 중인 단 하나의 액션을 실행하며, 새 prepare 호출 시 이전 대기 액션은 자동 무효화(superseded)됩니다. "
                    + "특정 액션을 지정하려면 prepare 응답의 actionId를 action_id로 전달하세요(생략 가능). "
                    + "prepare_reserve_library_seat(좌석 예약)는 예약 intent 큐를 통해 실행하고, "
                    + "prepare_cancel_library_seat(좌석 반납)와 prepare_swap_library_seat(자리 변경)는 직접 실행합니다. "
                    + "Requires mcp_session_id with the LIBRARY provider linked via start_auth."
    )
    public McpPrivateToolResponse<String> confirmAction(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id,
            @ToolParam(required = false,
                    description = "확정할 액션 ID (prepare 응답의 actionId). 생략하면 현재 대기 중인 단일 액션을 확정합니다.")
            Long action_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> confirmForSession(principal.sessionId(), principal.studentId(), action_id))
                .orElseGet(() -> {
                    log.debug("confirm_action: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> confirmForSession(
            String mcpSessionId, String sessionKey, Long actionId) {
        // Resolve which action this confirm targets BEFORE touching the upstream session,
        // so a no-target / ambiguous request is rejected without side effects.
        Long targetId = actionId;
        if (targetId == null) {
            List<ActionAudit> pendingActions = actionService.findActivePendingActions(sessionKey);
            if (pendingActions.isEmpty()) {
                return McpPrivateToolResponse.ok(
                        mcpSessionId, McpProviderType.LIBRARY.name(), "대기 중인 액션이 없습니다.");
            }
            if (pendingActions.size() > 1) {
                // Only reachable via a concurrent-prepare race; never guess which to execute.
                return McpPrivateToolResponse.ok(
                        mcpSessionId, McpProviderType.LIBRARY.name(),
                        "확정 대기 중인 액션이 여러 개입니다. 실행할 액션의 action_id를 지정해 다시 호출하세요.");
            }
            targetId = pendingActions.get(0).getId();
        }

        String token = sessionStore.token(sessionKey).orElse(null);
        if (token == null) {
            log.debug("confirm_action: library token missing, returning AUTH_REQUIRED");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }

        ActionAudit claimed;
        try {
            // Always claim by id (ownership + PENDING + TTL re-validated under a row lock):
            // for an explicit action_id this enforces the target belongs to the caller; for
            // the no-id path targetId is the caller's single active PENDING action.
            claimed = actionService.claimPendingActionById(sessionKey, targetId);
        } catch (ActionService.NoPendingActionException exception) {
            // Unknown id, not owned by the caller, or already executed/superseded/expired.
            // Never fall back to confirming a different action.
            String message = actionId != null
                    ? "지정한 action_id에 해당하는 대기 액션이 없습니다. (이미 실행/취소/만료됐거나 본인 세션의 액션이 아닙니다.)"
                    : "대기 중인 액션이 없습니다.";
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(), message);
        } catch (ActionService.ActionExpiredException exception) {
            return McpPrivateToolResponse.ok(
                    mcpSessionId, McpProviderType.LIBRARY.name(), "액션이 만료됐습니다. 다시 prepare 도구를 호출하세요.");
        }

        String actionType = claimed.getActionType();
        if (LibraryReservationMcpTool.ACTION_TYPE.equals(actionType)) {
            return executeReservationViaIntent(mcpSessionId, sessionKey, claimed);
        }
        if (LibraryCancelMcpTool.ACTION_TYPE.equals(actionType)) {
            return executeCancellation(mcpSessionId, claimed, token);
        }
        if (LibrarySwapMcpTool.ACTION_TYPE.equals(actionType)) {
            return executeSwap(mcpSessionId, claimed, token);
        }
        actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "지원하지 않는 액션 타입");
        return McpPrivateToolResponse.ok(
                mcpSessionId, McpProviderType.LIBRARY.name(), "지원하지 않는 대기 액션입니다.");
    }

    private McpPrivateToolResponse<String> executeReservationViaIntent(
            String mcpSessionId,
            String sessionKey,
            ActionAudit claimed) {
        LibraryReservationRequest request = actionService.payload(claimed, LibraryReservationRequest.class);
        LibraryReservationIntentView intent = intentTransactions.createImmediateReservation(
                sessionKey,
                claimed.getId(),
                request.seatId(),
                ActionService.ACTION_TTL);
        return awaitReservationIntent(mcpSessionId, claimed, intent.intentId());
    }

    private McpPrivateToolResponse<String> awaitReservationIntent(
            String mcpSessionId,
            ActionAudit claimed,
            Long intentId) {
        long deadline = System.nanoTime() + RESERVATION_INTENT_WAIT.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            Optional<LibraryReservationIntentView> current = intentTransactions.findById(intentId);
            if (current.isPresent() && isTerminal(current.get().status())) {
                return completeReservationFromIntent(mcpSessionId, claimed, current.get());
            }
            sleepQuietly();
        }
        actionService.completeAction(
                claimed,
                ActionService.OUTCOME_TIMEOUT,
                "Reservation intent still processing: intentId=" + intentId);
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                McpProviderType.LIBRARY.name(),
                "예약 intent가 처리 중입니다. intentId=" + intentId
                        + ". 같은 mcp_session_id로 get_library_wait_status를 호출해 최종 결과를 확인하세요.");
    }

    private McpPrivateToolResponse<String> completeReservationFromIntent(
            String mcpSessionId,
            ActionAudit claimed,
            LibraryReservationIntentView intent) {
        String detail = intent.outcomeMessage() == null ? "intentId=" + intent.intentId() : intent.outcomeMessage();
        if (intent.status() == LibraryReservationIntentStatus.SUCCEEDED) {
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS, detail);
            return McpPrivateToolResponse.ok(
                    mcpSessionId,
                    McpProviderType.LIBRARY.name(),
                    "예약 intent 큐 처리 완료. intentId=" + intent.intentId()
                            + ". " + detail);
        }
        if (intent.status() == LibraryReservationIntentStatus.FAILED_RACE) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_RACE, detail);
            return McpPrivateToolResponse.ok(
                    mcpSessionId,
                    McpProviderType.LIBRARY.name(),
                    "좌석이 이미 선점됐습니다. intentId=" + intent.intentId()
                            + ". recommend_library_seats와 prepare_reserve_library_seat로 다른 좌석을 다시 시도해주세요.");
        }
        if (intent.status() == LibraryReservationIntentStatus.FAILED_AUTH) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, detail);
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        }
        if (intent.status() == LibraryReservationIntentStatus.EXPIRED) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, detail);
            return McpPrivateToolResponse.ok(
                    mcpSessionId,
                    McpProviderType.LIBRARY.name(),
                    "예약 intent가 실행 전에 만료됐습니다. intentId=" + intent.intentId() + ".");
        }
        actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, detail);
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                McpProviderType.LIBRARY.name(),
                "예약 intent 실행에 실패했습니다. intentId=" + intent.intentId()
                        + ". get_library_wait_status로 상세 상태를 확인하세요.");
    }

    private McpPrivateToolResponse<String> executeCancellation(
            String mcpSessionId, ActionAudit claimed, String token) {
        LibraryCancelRequest request = actionService.payload(claimed, LibraryCancelRequest.class);
        try {
            reservationConnector.discharge(token, request.chargeId());
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    "예약 " + request.chargeId() + " 반납 완료");
            seatEventPublisher.cancel(request.roomId(), request.seatId());
            return McpPrivateToolResponse.ok(
                    mcpSessionId, McpProviderType.LIBRARY.name(),
                    "예약 번호 " + request.chargeId() + " 좌석 반납 완료.");
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "도서관 세션 만료");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "학교 서버 응답 없음");
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "학교 서버가 응답이 없어요. 잠시 후 다시 시도해주세요.");
        } catch (LibrarySeatNotAvailableException exception) {
            if (isNotAvailableState(exception)) {
                actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "미입실 상태로 반납 불가");
                return McpPrivateToolResponse.ok(
                        mcpSessionId, McpProviderType.LIBRARY.name(), notAvailableStateCancelMessage());
            }
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("confirm_action cancel failed", exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "반납 실행 오류");
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "실행 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    private McpPrivateToolResponse<String> executeSwap(
            String mcpSessionId, ActionAudit claimed, String token) {
        LibrarySwapRequest request = actionService.payload(claimed, LibrarySwapRequest.class);
        try {
            reservationConnector.discharge(token, request.oldChargeId());
            seatEventPublisher.swapDischarge(request.oldRoomId(), request.oldSeatId());
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "도서관 세션 만료");
            return authHelper.<String>buildAuthRequired(mcpSessionId, McpProviderType.LIBRARY);
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "학교 서버 응답 없음 (반납 단계)");
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "학교 서버가 응답이 없어 자리 변경을 못 했어요. 잠시 후 다시 시도해주세요.");
        } catch (LibrarySeatNotAvailableException exception) {
            if (isNotAvailableState(exception)) {
                actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "미입실 상태로 이석 불가");
                return McpPrivateToolResponse.ok(
                        mcpSessionId, McpProviderType.LIBRARY.name(), notAvailableStateSwapMessage());
            }
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("confirm_action swap: discharge failed seat={}", request.newSeatId(), exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "기존 좌석 반납 실패");
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "기존 좌석 반납에 실패해 자리 변경을 못 했어요. 잠시 후 다시 시도해주세요.");
        }

        try {
            LibraryReservationResult result = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.newSeatId()));
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    result.roomName() + " " + result.seatCode() + "번으로 변경 완료");
            seatEventPublisher.swapReserve(
                    result.roomId(),
                    result.seatId() == null ? request.newSeatId() : result.seatId());
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(), String.format(
                    "자리 변경 완료! %s %s번 예약 완료. 이용시간: %s ~ %s (예약번호: %d, 반납 시 필요)",
                    result.roomName(), result.seatCode(),
                    result.beginTime(), result.endTime(), result.chargeId()));
        } catch (LibrarySeatNotAvailableException exception) {
            log.warn("confirm_action swap: discharge succeeded but seat not available seat={}", request.newSeatId());
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_RACE,
                    "기존 좌석 반납 후 새 좌석 선점됨");
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "기존 좌석은 반납됐으나 새 좌석(" + request.newSeatId() + "번)이 이미 선점됐습니다. "
                            + "recommend_library_seats로 다른 좌석을 추천받아 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            log.warn("confirm_action swap: discharge succeeded but reserve failed seat={}", request.newSeatId(), exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM,
                    "기존 좌석 반납 후 새 좌석 예약 실패");
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "기존 좌석은 반납됐으나 새 좌석(" + request.newSeatId() + "번) 예약에 실패했습니다. "
                            + "prepare_reserve_library_seat로 다시 시도해주세요.");
        }
    }

    private static boolean isTerminal(LibraryReservationIntentStatus status) {
        return switch (status) {
            case SUCCEEDED, FAILED_RACE, FAILED_AUTH, FAILED_UPSTREAM, CANCELLED, EXPIRED -> true;
            case REQUESTED, WAITING_FOR_SEAT, RESERVING -> false;
        };
    }

    private static boolean isNotAvailableState(LibrarySeatNotAvailableException exception) {
        return NOT_AVAILABLE_STATE_CODE.equals(exception.getPyxisCode());
    }

    private static String notAvailableStateCancelMessage() {
        return "아직 입실 전이라 좌석을 반납할 수 없어요. "
                + "도서관 게이트/NFC로 입실한 뒤 다시 시도해주세요. "
                + "미입실 배정 좌석은 도서관 정책에 따라 일정 시간 후 자동 취소될 수 있습니다.";
    }

    private static String notAvailableStateSwapMessage() {
        return "아직 입실 전이라 자리 변경을 할 수 없어요. 기존 예약은 그대로 유지됐습니다. "
                + "도서관 게이트/NFC로 입실한 뒤 다시 시도해주세요. "
                + "미입실 배정 좌석은 도서관 정책에 따라 일정 시간 후 자동 취소될 수 있습니다.";
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(RESERVATION_INTENT_POLL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
