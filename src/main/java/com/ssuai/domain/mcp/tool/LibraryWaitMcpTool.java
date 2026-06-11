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
            description = "Register a background wait intent for a Soongsil central-library seat. "
                    + "This call itself is the user's consent: after registration, when a matching seat opens, "
                    + "the server worker may autonomously reserve it without a later confirm_action call. "
                    + "Requires mcp_session_id linked with start_auth(LIBRARY)."
    )
    public McpPrivateToolResponse<String> waitForLibrarySeat(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id,
            @ToolParam(required = false, description = "Preferred floor: 2, 5, 6, 2F, 5F, or 6F.")
            String floor,
            @ToolParam(required = false, description = "Preferred Pyxis room IDs as JSON array or CSV, e.g. [57,58] or 57,58.")
            String room_ids,
            @ToolParam(required = false, description = "Required attributes as JSON object/array or CSV: window,outlet,standing,edge,quiet,nearEntrance.")
            String seat_attributes,
            @ToolParam(required = false, description = "Fixed Pyxis seat ID to reserve when available.")
            String target_seat_id,
            @ToolParam(required = false, description = "Wait expiry in minutes. Defaults to 120 minutes.")
            Integer expires_in_minutes
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> register(mcp_session_id, sessionKey,
                        floor, room_ids, seat_attributes, target_seat_id, expires_in_minutes))
                .orElseGet(() -> {
                    log.debug("wait_for_library_seat: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    @Tool(
            name = "get_library_wait_status",
            description = "Get the latest library seat wait intent status. Requires mcp_session_id linked with start_auth(LIBRARY)."
    )
    public McpPrivateToolResponse<String> getLibraryWaitStatus(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> {
                    Optional<LibraryReservationIntentView> latest = transactions.latestForSession(sessionKey);
                    return McpPrivateToolResponse.ok(mcp_session_id,
                            latest.map(this::statusMessage).orElse("No library seat wait intent exists."));
                })
                .orElseGet(() -> {
                    log.debug("get_library_wait_status: LIBRARY not linked, returning AUTH_REQUIRED");
                    return authHelper.<String>buildAuthRequired(mcp_session_id, McpProviderType.LIBRARY);
                });
    }

    @Tool(
            name = "cancel_library_wait",
            description = "Cancel the active library seat wait intent if it has not started reserving. "
                    + "Requires mcp_session_id linked with start_auth(LIBRARY)."
    )
    public McpPrivateToolResponse<String> cancelLibraryWait(
            @ToolParam(description = "MCP session ID issued by start_auth(LIBRARY).")
            String mcp_session_id
    ) {
        return authHelper.principalKey(mcp_session_id, McpProviderType.LIBRARY)
                .map(sessionKey -> {
                    Optional<LibraryReservationIntentView> cancelled = transactions.cancelActive(sessionKey);
                    return McpPrivateToolResponse.ok(mcp_session_id,
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
        return McpPrivateToolResponse.ok(mcpSessionId, prefix + statusMessage(result.intent())
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

    private static Long parseSeatId(String targetSeatId) {
        if (targetSeatId == null || targetSeatId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(targetSeatId.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("target_seat_id must be a number.");
        }
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
