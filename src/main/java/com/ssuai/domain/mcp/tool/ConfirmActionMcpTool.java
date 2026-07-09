package com.ssuai.domain.mcp.tool;

import java.util.List;
import java.util.stream.Collectors;

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
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

/**
 * Shared confirm step for library write actions (ADR 0015).
 *
 * <p>Reserve confirms create an immediate reservation intent and return ACCEPTED right away
 * (ADR 0086 / C1) — the async worker resolves it and the caller polls
 * {@code get_library_wait_status}. Cancel/swap execute synchronously; only the reserve path is
 * routed through the intent queue.
 */
@Component
public class ConfirmActionMcpTool {

    private static final Logger log = LoggerFactory.getLogger(ConfirmActionMcpTool.class);
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
                    + "대기 중인 액션이 하나뿐이면 action_id 없이 그 액션을 실행합니다. "
                    + "같은 좌석/예약을 다시 prepare하면 이전 대기 액션이 자동 무효화(superseded)되지만, "
                    + "서로 다른 좌석 등 별개의 액션을 여러 개 prepare하면 각각 별도로 대기하며 "
                    + "이 경우 confirm_action은 실행하지 않고 대기 중인 action_id 목록을 안내합니다. "
                    + "특정 액션을 지정하려면 prepare 응답의 actionId를 action_id로 전달하세요. "
                    + "prepare_reserve_library_seat(좌석 예약)는 예약 intent 큐를 통해 비동기로 접수만 하고 즉시 반환하며, "
                    + "최종 결과는 get_library_wait_status로 확인합니다. "
                    + "prepare_cancel_library_seat(좌석 반납)와 prepare_swap_library_seat(자리 변경)는 직접 실행합니다. "
                    + "mcp_session_id 필요(LIBRARY 로그인)."
    )
    public McpPrivateToolResponse<String> confirmAction(
            @ToolParam(description = "start_auth(LIBRARY)로 발급받은 MCP session ID.")
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
                // Multiple concurrent actions of the same owner (e.g. two different pending seat
                // reservations, ADR 0086) or a concurrent-prepare race; never guess which to
                // execute — list every candidate so the caller can pick the right action_id.
                return McpPrivateToolResponse.ok(
                        mcpSessionId, McpProviderType.LIBRARY.name(),
                        "확정 대기 중인 액션이 여러 개입니다. 실행할 액션의 action_id를 지정해 다시 호출하세요. 대기 중: "
                                + describePendingActions(pendingActions));
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

    /**
     * Creates the immediate reservation intent and returns IMMEDIATELY (ADR 0086 / C1) —
     * never blocks the calling servlet thread waiting for the async worker. See
     * {@link #acceptedReservationResponse} for why: the MCP transport here is SYNC/Streamable
     * HTTP (spring-ai {@code mcp-spring-webmvc}), which invokes {@code @Tool} methods, including
     * this one, directly on the Tomcat request thread and offers no async-dispatch escape hatch
     * — so ANY in-method sleep/poll, however short, still consumes one pooled thread per
     * in-flight confirm. Removing the wait entirely (rather than shortening it) is what actually
     * fixes the N-concurrent-confirms-exhaust-the-pool failure mode C1 describes.
     */
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
        return acceptedReservationResponse(mcpSessionId, intent.intentId());
    }

    /**
     * Deterministic, non-blocking response for an accepted (not-yet-resolved) reservation
     * intent. This path is <em>observe-only</em> with respect to the {@link ActionAudit}: it
     * NEVER writes the audit's terminal outcome. The async reservation worker
     * ({@code LibraryReservationWorker}, woken immediately after commit) is the single source of
     * truth for both the intent's terminal state and the linked audit's terminal outcome
     * (finalized together in one transaction). The caller (LLM or ssuAgent HITL loop) always has
     * a deterministic way to learn the outcome: {@code get_library_wait_status(mcp_session_id,
     * intent_id)}.
     */
    private McpPrivateToolResponse<String> acceptedReservationResponse(String mcpSessionId, Long intentId) {
        return McpPrivateToolResponse.ok(
                mcpSessionId,
                McpProviderType.LIBRARY.name(),
                "예약 요청을 접수했습니다. intentId=" + intentId
                        + ". 보통 수 초 내 처리됩니다. 같은 mcp_session_id로 get_library_wait_status(intent_id="
                        + intentId + ")를 호출해 최종 결과를 확인하세요.");
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
            // Old seat already released, new seat taken by a racer. The upstream has no atomic
            // swap, so we compensate by re-reserving the original seat.
            log.warn("confirm_action swap: discharge succeeded but new seat not available seat={}", request.newSeatId());
            return compensateSwap(mcpSessionId, claimed, token, request,
                    "이미 선점됐습니다", "recommend_library_seats로 다른 좌석을 추천받아 다시 시도해주세요.");
        } catch (RuntimeException exception) {
            // Old seat already released, new-seat reserve failed upstream. Same compensation path.
            log.warn("confirm_action swap: discharge succeeded but reserve failed seat={}", request.newSeatId(), exception);
            return compensateSwap(mcpSessionId, claimed, token, request,
                    "예약에 실패했습니다", "prepare_reserve_library_seat로 다시 시도해주세요.");
        }
    }

    /**
     * Compensating action for a non-atomic swap. The old seat is already released and
     * the new-seat reservation failed, so attempt to re-reserve the ORIGINAL seat to restore the
     * user's prior state.
     *
     * <ul>
     *   <li>Compensation succeeds → report the swap failed but the original seat is RETAINED, and
     *       re-publish the original seat as reserved (swapDischarge already marked it free, so the
     *       seat map would otherwise be stale).</li>
     *   <li>Compensation ALSO fails (e.g. the old seat was taken in between) → return a distinct
     *       {@link ActionService#OUTCOME_PARTIAL_FAILURE} outcome telling the user they currently
     *       hold NO seat and must re-reserve, logged at warn for operator visibility. The old seat
     *       stays free in the seat map, which is correct.</li>
     * </ul>
     */
    private McpPrivateToolResponse<String> compensateSwap(
            String mcpSessionId,
            ActionAudit claimed,
            String token,
            LibrarySwapRequest request,
            String newSeatFailureReason,
            String newSeatRetryHint) {
        if (request.oldSeatId() == null) {
            // Defensive: production prepare always populates oldSeatId/oldRoomId; without it we
            // cannot identify the original seat to re-reserve. Fall back to PARTIAL_FAILURE.
            log.warn("confirm_action swap: cannot compensate, original seat id is missing newSeat={}",
                    request.newSeatId());
            return partialSwapFailure(mcpSessionId, claimed, request, newSeatFailureReason);
        }
        try {
            LibraryReservationResult restored = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.oldSeatId()));
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_RACE,
                    "새 좌석 " + newSeatFailureReason + " · 기존 좌석 재예약으로 복구됨");
            // swapDischarge already freed the old seat in the map; re-publish it as reserved.
            seatEventPublisher.swapReserve(
                    restored.roomId() == null ? request.oldRoomId() : restored.roomId(),
                    restored.seatId() == null ? request.oldSeatId() : restored.seatId());
            return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                    "자리 변경에 실패했어요. 새 좌석(" + request.newSeatId() + "번)이 " + newSeatFailureReason
                            + ". 기존 좌석은 다시 예약해 그대로 유지했으니 안심하세요. "
                            + "다른 좌석으로 옮기려면 " + newSeatRetryHint);
        } catch (RuntimeException compensationFailure) {
            log.warn("confirm_action swap: compensation re-reserve of original seat {} FAILED; "
                            + "user now holds NO seat newSeat={}",
                    request.oldSeatId(), request.newSeatId(), compensationFailure);
            return partialSwapFailure(mcpSessionId, claimed, request, newSeatFailureReason);
        }
    }

    private McpPrivateToolResponse<String> partialSwapFailure(
            String mcpSessionId,
            ActionAudit claimed,
            LibrarySwapRequest request,
            String newSeatFailureReason) {
        actionService.completeAction(claimed, ActionService.OUTCOME_PARTIAL_FAILURE,
                "기존 좌석 반납 후 새 좌석 예약 실패 · 기존 좌석 복구도 실패");
        return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                "자리 변경에 실패했고 기존 좌석 복구도 실패했어요. 현재 예약된 좌석이 하나도 없는 상태입니다. "
                        + "새 좌석(" + request.newSeatId() + "번)이 " + newSeatFailureReason
                        + ". prepare_reserve_library_seat로 좌석을 다시 예약해주세요.");
    }

    private static String describePendingActions(List<ActionAudit> pendingActions) {
        return pendingActions.stream()
                .map(action -> "action_id=" + action.getId() + "(" + action.getActionType() + ")")
                .collect(Collectors.joining(", "));
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
}
