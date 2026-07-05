package com.ssuai.domain.mcp.tool;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.domain.library.reservation.intent.LibraryReservationPreferenceNormalizer;
import com.ssuai.domain.library.reservation.intent.LibraryReservationRegistrationResult;
import com.ssuai.domain.library.reservation.intent.LibraryReservationWaitRequest;

@Component
public class LibraryWaitMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LibraryWaitMcpTool.class);

    private final LibraryReservationIntentTransactions transactions;
    private final LibraryReservationPreferenceNormalizer preferenceNormalizer;
    private final McpAuthHelper authHelper;

    public LibraryWaitMcpTool(
            LibraryReservationIntentTransactions transactions,
            LibraryReservationPreferenceNormalizer preferenceNormalizer,
            McpAuthHelper authHelper) {
        this.transactions = transactions;
        this.preferenceNormalizer = preferenceNormalizer;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "wait_for_library_seat",
            description = "숭실대학교 중앙도서관 좌석에 대한 백그라운드 대기 intent를 등록합니다. "
                    + "이 호출 자체가 사용자의 동의이며, 등록 후 조건에 맞는 좌석이 나오면 "
                    + "서버 워커가 이후 confirm_action 호출 없이 자동으로 예약할 수 있습니다. "
                    + "mcp_session_id 필요(LIBRARY 로그인)."
    )
    public McpPrivateToolResponse<String> waitForLibrarySeat(
            @ToolParam(description = "start_auth(LIBRARY)로 발급받은 MCP session ID.")
            String mcp_session_id,
            @ToolParam(required = false, description = "선호 층: 2, 5, 6, 2F, 5F, 6F 중 하나.")
            String floor,
            @ToolParam(required = false, description = "선호 Pyxis room ID. JSON 배열 또는 CSV 형식, 예: [57,58] 또는 57,58.")
            String room_ids,
            @ToolParam(required = false, description = "필수 속성. JSON 객체/배열 또는 CSV 형식: window,outlet,standing,edge,quiet,nearEntrance.")
            String seat_attributes,
            @ToolParam(required = false, description = "가용 시 예약할 고정 Pyxis 좌석 ID.")
            String target_seat_id,
            @ToolParam(required = false, description = "대기 만료 시간(분). 기본 120분.")
            Integer expires_in_minutes
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> register(principal.sessionId(), principal.studentId(),
                        floor, room_ids, seat_attributes, target_seat_id, expires_in_minutes))
                .orElseGet(() -> {
                    log.debug("wait_for_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    @Tool(
            name = "get_library_wait_status",
            description = "최신 도서관 좌석 대기 intent 상태를 조회합니다. mcp_session_id 필요(LIBRARY 로그인)."
    )
    public McpPrivateToolResponse<String> getLibraryWaitStatus(
            @ToolParam(description = "start_auth(LIBRARY)로 발급받은 MCP session ID.")
            String mcp_session_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> {
                    Optional<LibraryReservationIntentView> latest = transactions.latestForSession(principal.studentId());
                    return McpPrivateToolResponse.ok(principal.sessionId(), McpProviderType.LIBRARY.name(),
                            latest.map(this::statusMessage).orElse("No library seat wait intent exists."));
                })
                .orElseGet(() -> {
                    log.debug("get_library_wait_status: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    @Tool(
            name = "cancel_library_wait",
            description = "아직 예약이 시작되지 않은 활성 도서관 좌석 대기 intent를 취소합니다. "
                    + "mcp_session_id 필요(LIBRARY 로그인)."
    )
    public McpPrivateToolResponse<String> cancelLibraryWait(
            @ToolParam(description = "start_auth(LIBRARY)로 발급받은 MCP session ID.")
            String mcp_session_id
    ) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.LIBRARY)
                .map(principal -> {
                    Optional<LibraryReservationIntentView> cancelled = transactions.cancelActive(principal.studentId());
                    return McpPrivateToolResponse.ok(principal.sessionId(), McpProviderType.LIBRARY.name(),
                            cancelled.map(this::cancelMessage).orElse("No active library seat wait intent exists."));
                })
                .orElseGet(() -> {
                    log.debug("cancel_library_wait: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    private McpPrivateToolResponse<String> register(
            String mcpSessionId,
            String sessionKey,
            String floor,
            String roomIds,
            String seatAttributes,
            String targetSeatId,
            Integer expiresInMinutes) {
        LibraryReservationWaitRequest request = new LibraryReservationWaitRequest(
                preferenceNormalizer.normalizeFloor(floor),
                preferenceNormalizer.normalizeRoomIds(roomIds),
                preferenceNormalizer.normalizeSeatAttributes(seatAttributes),
                parseSeatId(targetSeatId),
                parseExpiry(expiresInMinutes));
        LibraryReservationRegistrationResult result = transactions.registerWait(sessionKey, request);
        String prefix = result.newlyCreated()
                ? "Library seat wait registered. "
                : "An active library seat wait already exists; returning it. ";
        return McpPrivateToolResponse.ok(mcpSessionId, McpProviderType.LIBRARY.name(),
                prefix + statusMessage(result.intent())
                + " After registration, a worker may autonomously reserve a matching seat when one opens.");
    }

    private String statusMessage(LibraryReservationIntentView view) {
        return "intentId=%d, status=%s, attempts=%d, targetSeatId=%s, nextAttemptAt=%s, expiresAt=%s, completedAt=%s, outcome=%s, message=%s. Next action: use get_library_wait_status to poll, or cancel_library_wait before reservation starts."
                .formatted(
                        view.intentId(),
                        view.status(),
                        view.attemptCount(),
                        view.targetSeatId() == null ? "ANY" : view.targetSeatId(),
                        view.nextAttemptAt(),
                        view.expiresAt(),
                        view.completedAt(),
                        view.outcomeCode(),
                        view.outcomeMessage());
    }

    private String cancelMessage(LibraryReservationIntentView view) {
        if ("RESERVING".equals(view.status().name())) {
            return "intentId=%d is already RESERVING and cannot be cancelled safely. Use get_library_wait_status."
                    .formatted(view.intentId());
        }
        return "Library wait updated. " + statusMessage(view);
    }

    static Long parseSeatId(String targetSeatId) {
        if (targetSeatId == null || targetSeatId.isBlank()) {
            return null;
        }
        long seatId;
        try {
            seatId = Long.parseLong(targetSeatId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("target_seat_id must be a number.");
        }
        if (seatId <= 0) {
            throw new IllegalArgumentException("target_seat_id must be a positive number.");
        }
        return seatId;
    }

    private static Duration parseExpiry(Integer expiresInMinutes) {
        if (expiresInMinutes == null) {
            return null;
        }
        if (expiresInMinutes <= 0) {
            throw new IllegalArgumentException("expires_in_minutes must be positive.");
        }
        return Duration.ofMinutes(expiresInMinutes);
    }
}
