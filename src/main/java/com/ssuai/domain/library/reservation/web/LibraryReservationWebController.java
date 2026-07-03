package com.ssuai.domain.library.reservation.web;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.recommendation.LibrarySeatPreference;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationResponse;
import com.ssuai.domain.library.recommendation.LibrarySeatRecommendationService;
import com.ssuai.domain.mcp.tool.LibraryCancelMcpTool;
import com.ssuai.domain.mcp.tool.LibraryReservationMcpTool;
import com.ssuai.domain.mcp.tool.LibrarySwapMcpTool;
import com.ssuai.domain.library.reservation.LibraryCancelRequest;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.domain.library.reservation.LibrarySwapRequest;
import com.ssuai.domain.library.reservation.intent.LibraryIntentSseRegistry;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentTransactions;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentView;
import com.ssuai.domain.library.reservation.intent.LibraryReservationRegistrationResult;
import com.ssuai.domain.library.reservation.intent.LibraryReservationWaitRequest;
import com.ssuai.global.exception.ApiException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;
import com.ssuai.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/library/reservations")
public class LibraryReservationWebController {

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationWebController.class);
    private static final String NOT_AVAILABLE_STATE_CODE = "warning.smuf.notAvailableState";

    // Non-final so tests can shrink the sync wait without a real 8s sleep. Production keeps
    // the defaults; the values are not configurable at runtime (mirrors ConfirmActionMcpTool).
    private Duration reservationIntentWait = Duration.ofSeconds(8);
    private Duration reservationIntentPoll = Duration.ofMillis(200);

    private final ActionService actionService;
    private final LibrarySessionStore librarySessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final LibraryReservationIntentTransactions intentTransactions;
    private final LibrarySeatEventPublisher seatEventPublisher;
    private final LibrarySeatRecommendationService recommendationService;
    private final LibraryIntentSseRegistry intentSseRegistry;

    public LibraryReservationWebController(
            ActionService actionService,
            LibrarySessionStore librarySessionStore,
            LibraryReservationConnector reservationConnector,
            LibraryReservationIntentTransactions intentTransactions,
            LibrarySeatEventPublisher seatEventPublisher,
            LibrarySeatRecommendationService recommendationService,
            LibraryIntentSseRegistry intentSseRegistry) {
        this.actionService = actionService;
        this.librarySessionStore = librarySessionStore;
        this.reservationConnector = reservationConnector;
        this.intentTransactions = intentTransactions;
        this.seatEventPublisher = seatEventPublisher;
        this.recommendationService = recommendationService;
        this.intentSseRegistry = intentSseRegistry;
    }

    @GetMapping("/recommend")
    public ApiResponse<LibrarySeatRecommendationResponse> recommend(
            @RequestParam int floor,
            @RequestParam(required = false) String roomIds,
            @RequestParam(required = false) String attributes,
            HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        LibrarySeatPreference preference = preferenceFrom(attributes);
        return ApiResponse.success(recommendationService.recommend(
                com.ssuai.domain.library.dto.LibraryFloor.fromCode(floor),
                sessionKey,
                preference,
                null));
    }

    @PostMapping("/prepare")
    public ApiResponse<LibraryReservationPrepareResponse> prepare(
            @RequestBody LibraryReservationPrepareRequest request,
            HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        String token = librarySessionStore.token(sessionKey)
                .orElseThrow(LibraryAuthRequiredException::new);
        String type = normalizeType(request.type());

        if ("RESERVE".equals(type)) {
            return ApiResponse.success(prepareReserve(sessionKey, request));
        }
        if ("CANCEL".equals(type)) {
            return ApiResponse.success(prepareCancel(sessionKey, token));
        }
        if ("SWAP".equals(type)) {
            return ApiResponse.success(prepareSwap(sessionKey, token, request));
        }
        throw new IllegalArgumentException("type must be RESERVE, CANCEL, or SWAP.");
    }

    @PostMapping("/confirm")
    public ApiResponse<LibraryReservationConfirmResponse> confirm(HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        String token = librarySessionStore.token(sessionKey)
                .orElseThrow(LibraryAuthRequiredException::new);
        ActionAudit pending = actionService.findPendingAction(sessionKey).orElse(null);
        if (pending == null) {
            return ApiResponse.success(new LibraryReservationConfirmResponse(
                    "FAILED_UPSTREAM",
                    null,
                    "No pending library action exists."));
        }
        if (actionService.isExpired(pending)) {
            actionService.expirePending(sessionKey);
            return ApiResponse.success(new LibraryReservationConfirmResponse(
                    "TIMEOUT",
                    null,
                    "Pending library action expired. Please prepare again."));
        }

        ActionAudit claimed;
        try {
            claimed = actionService.claimPendingAction(sessionKey);
        } catch (ActionService.NoPendingActionException exception) {
            return ApiResponse.success(new LibraryReservationConfirmResponse(
                    "FAILED_UPSTREAM",
                    null,
                    "No pending library action exists."));
        } catch (ActionService.ActionExpiredException exception) {
            return ApiResponse.success(new LibraryReservationConfirmResponse(
                    "TIMEOUT",
                    null,
                    "Pending library action expired. Please prepare again."));
        }

        String actionType = claimed.getActionType();
        if (LibraryReservationMcpTool.ACTION_TYPE.equals(actionType)) {
            return ApiResponse.success(executeReservationViaIntent(sessionKey, claimed));
        }
        if (LibraryCancelMcpTool.ACTION_TYPE.equals(actionType)) {
            return ApiResponse.success(executeCancellation(claimed, token));
        }
        if (LibrarySwapMcpTool.ACTION_TYPE.equals(actionType)) {
            return ApiResponse.success(executeSwap(claimed, token));
        }
        actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "Unknown library action type.");
        return ApiResponse.success(new LibraryReservationConfirmResponse(
                "FAILED_UPSTREAM",
                null,
                "Unknown library action type."));
    }

    @PostMapping("/wait")
    public ApiResponse<LibraryReservationIntentView> registerWait(
            @RequestBody LibraryReservationWaitWebRequest request,
            HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        LibraryReservationRegistrationResult result = intentTransactions.registerWait(sessionKey,
                new LibraryReservationWaitRequest(
                        request.preferredFloor(),
                        request.preferredRoomIds(),
                        request.seatAttributes(),
                        request.targetSeatId(),
                        null));
        if (!result.newlyCreated()) {
            throw new ApiException(ErrorCode.ACTIVE_WAIT_EXISTS);
        }
        return ApiResponse.success(result.intent());
    }

    @GetMapping("/wait/current")
    public ApiResponse<LibraryReservationIntentView> currentWait(HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        return ApiResponse.success(intentTransactions.latestForSession(sessionKey)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    @DeleteMapping("/wait")
    public ApiResponse<LibraryReservationIntentView> cancelWait(HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        return ApiResponse.success(intentTransactions.cancelActive(sessionKey)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    @GetMapping(value = "/wait/events/{intentId}", produces = "text/event-stream")
    public SseEmitter intentEvents(
            @PathVariable Long intentId,
            HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        // IDOR guard: a valid library session alone is NOT enough — the intentId must
        // belong to THIS caller's session. Otherwise a guessable/sequential intentId lets one
        // user subscribe to another user's reservation result/seat. Reject with 404 (not 403) so
        // we never confirm whether the intentId exists, and crucially do NOT open the stream.
        if (!intentTransactions.isOwnedBySession(intentId, sessionKey)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return intentSseRegistry.createEmitter(intentId);
    }

    @GetMapping("/my-seat")
    public ApiResponse<LibraryReservationResult> mySeat(HttpServletRequest httpRequest) {
        String sessionKey = requireLibrarySession(httpRequest);
        String token = librarySessionStore.token(sessionKey)
                .orElseThrow(LibraryAuthRequiredException::new);
        return ApiResponse.success(reservationConnector.getCurrentCharge(token)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }

    private LibraryReservationConfirmResponse executeReservationViaIntent(
            String sessionKey,
            ActionAudit claimed) {
        LibraryReservationRequest request = actionService.payload(claimed, LibraryReservationRequest.class);
        LibraryReservationIntentView intent = intentTransactions.createImmediateReservation(
                sessionKey,
                claimed.getId(),
                request.seatId(),
                ActionService.ACTION_TTL);
        return awaitReservationIntent(intent.intentId());
    }

    /**
     * Observe-only sync wait for the async reservation worker, mirroring
     * {@code ConfirmActionMcpTool}. It NEVER writes the {@link ActionAudit} terminal outcome:
     * the worker is the single source of truth and finalizes the linked audit in the same
     * transaction that makes the intent terminal. On timeout the audit is left
     * EXECUTING (a timeout is a response state, not a business failure) and the caller gets a
     * non-terminal {@code PROCESSING} status so the still-running worker can finalize it.
     */
    private LibraryReservationConfirmResponse awaitReservationIntent(Long intentId) {
        long deadline = System.nanoTime() + reservationIntentWait.toNanos();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            Optional<LibraryReservationIntentView> current = intentTransactions.findById(intentId);
            if (current.isPresent() && isTerminal(current.get().status())) {
                return describeReservationFromIntent(current.get());
            }
            sleepQuietly();
        }
        return new LibraryReservationConfirmResponse(
                "PROCESSING",
                intentId,
                "Reservation is still being completed in the background. Check the wait status later.");
    }

    private LibraryReservationConfirmResponse describeReservationFromIntent(
            LibraryReservationIntentView intent) {
        String detail = intent.outcomeMessage() == null ? "intentId=" + intent.intentId() : intent.outcomeMessage();
        if (intent.status() == LibraryReservationIntentStatus.SUCCEEDED) {
            return new LibraryReservationConfirmResponse("SUCCESS", intent.intentId(), detail);
        }
        if (intent.status() == LibraryReservationIntentStatus.FAILED_RACE) {
            return new LibraryReservationConfirmResponse("FAILED_RACE", intent.intentId(), detail);
        }
        if (intent.status() == LibraryReservationIntentStatus.FAILED_AUTH) {
            return new LibraryReservationConfirmResponse("FAILED_AUTH", intent.intentId(), detail);
        }
        if (intent.status() == LibraryReservationIntentStatus.EXPIRED) {
            return new LibraryReservationConfirmResponse("TIMEOUT", intent.intentId(), detail);
        }
        return new LibraryReservationConfirmResponse("FAILED_UPSTREAM", intent.intentId(), detail);
    }

    private LibraryReservationConfirmResponse executeCancellation(ActionAudit claimed, String token) {
        LibraryCancelRequest request = actionService.payload(claimed, LibraryCancelRequest.class);
        try {
            reservationConnector.discharge(token, request.chargeId());
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    "Reservation cancelled: " + request.chargeId());
            seatEventPublisher.cancel(request.roomId(), request.seatId());
            return new LibraryReservationConfirmResponse(
                    "SUCCESS",
                    null,
                    "Reservation cancelled: " + request.chargeId());
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "Library session expired.");
            return new LibraryReservationConfirmResponse("FAILED_AUTH", null, "Library session expired.");
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "Upstream timeout.");
            return new LibraryReservationConfirmResponse("TIMEOUT", null, "Upstream timeout.");
        } catch (LibrarySeatNotAvailableException exception) {
            if (isNotAvailableState(exception)) {
                actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM,
                        "Seat was already in a non-reservable state.");
                return new LibraryReservationConfirmResponse(
                        "FAILED_UPSTREAM",
                        null,
                        "Seat was already in a non-reservable state.");
            }
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("confirm reservation cancel failed", exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "Cancellation failed.");
            return new LibraryReservationConfirmResponse("FAILED_UPSTREAM", null, "Cancellation failed.");
        }
    }

    private LibraryReservationConfirmResponse executeSwap(ActionAudit claimed, String token) {
        LibrarySwapRequest request = actionService.payload(claimed, LibrarySwapRequest.class);
        try {
            reservationConnector.discharge(token, request.oldChargeId());
            seatEventPublisher.swapDischarge(request.oldRoomId(), request.oldSeatId());
        } catch (LibraryAuthRequiredException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_AUTH, "Library session expired.");
            return new LibraryReservationConfirmResponse("FAILED_AUTH", null, "Library session expired.");
        } catch (ConnectorTimeoutException exception) {
            actionService.completeAction(claimed, ActionService.OUTCOME_TIMEOUT, "Upstream timeout during discharge.");
            return new LibraryReservationConfirmResponse(
                    "TIMEOUT",
                    null,
                    "Upstream timeout during discharge.");
        } catch (LibrarySeatNotAvailableException exception) {
            if (isNotAvailableState(exception)) {
                actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM,
                        "Current seat was already in a non-reservable state.");
                return new LibraryReservationConfirmResponse(
                        "FAILED_UPSTREAM",
                        null,
                        "Current seat was already in a non-reservable state.");
            }
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("confirm reservation swap discharge failed", exception);
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_UPSTREAM, "Swap discharge failed.");
            return new LibraryReservationConfirmResponse("FAILED_UPSTREAM", null, "Swap discharge failed.");
        }

        try {
            LibraryReservationResult result = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.newSeatId()));
            actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS,
                    result.roomName() + " " + result.seatCode() + " reserved.");
            seatEventPublisher.swapReserve(
                    result.roomId(),
                    result.seatId() == null ? request.newSeatId() : result.seatId());
            return new LibraryReservationConfirmResponse(
                    "SUCCESS",
                    null,
                    result.roomName() + " " + result.seatCode() + " reserved.");
        } catch (LibrarySeatNotAvailableException exception) {
            // Old seat already released, new seat taken by a racer. The upstream has no atomic
            // swap, so compensate by re-reserving the original seat (mirrors
            // ConfirmActionMcpTool.compensateSwap — the MCP path had this guard, the web path did not).
            log.warn("confirm reservation swap: discharge succeeded but new seat unavailable seat={}",
                    request.newSeatId());
            return compensateSwap(claimed, token, request, "Target seat became unavailable.");
        } catch (RuntimeException exception) {
            log.warn("confirm reservation swap: discharge succeeded but reserve failed seat={}",
                    request.newSeatId(), exception);
            return compensateSwap(claimed, token, request, "Swap reserve failed.");
        }
    }

    /**
     * Compensating action for a non-atomic swap. The old seat is already released and the new-seat
     * reservation failed, so re-reserve the ORIGINAL seat to restore the user's prior state. Mirrors
     * {@code ConfirmActionMcpTool.compensateSwap} so the web confirm path has the same safety
     * guarantee as the MCP path (previously the web path returned failure without restoring the
     * old seat, leaving the user holding NO seat).
     *
     * <ul>
     *   <li>Compensation succeeds → {@code OUTCOME_FAILURE_RACE}; re-publish the original seat as
     *       reserved (swapDischarge already freed it in the seat map) and tell the user the seat is
     *       retained.</li>
     *   <li>Compensation also fails → {@code OUTCOME_PARTIAL_FAILURE}; the user holds no seat and
     *       must re-reserve (logged at warn for operator visibility).</li>
     * </ul>
     */
    private LibraryReservationConfirmResponse compensateSwap(
            ActionAudit claimed, String token, LibrarySwapRequest request, String newSeatFailureReason) {
        if (request.oldSeatId() == null) {
            // Defensive: prepare always populates oldSeatId; without it we cannot restore.
            log.warn("confirm reservation swap: cannot compensate, original seat id missing newSeat={}",
                    request.newSeatId());
            return partialSwapFailure(claimed);
        }
        try {
            LibraryReservationResult restored = reservationConnector.reserve(
                    token, new LibraryReservationRequest(request.oldSeatId()));
            actionService.completeAction(claimed, ActionService.OUTCOME_FAILURE_RACE,
                    "New seat " + newSeatFailureReason + " — original seat re-reserved (compensated).");
            // swapDischarge already freed the old seat in the map; re-publish it as reserved.
            seatEventPublisher.swapReserve(
                    restored.roomId() == null ? request.oldRoomId() : restored.roomId(),
                    restored.seatId() == null ? request.oldSeatId() : restored.seatId());
            return new LibraryReservationConfirmResponse(
                    "FAILED_RACE",
                    null,
                    "자리 변경에 실패했지만 기존 좌석은 다시 예약해 그대로 유지했어요. "
                            + "다른 좌석으로 옮기려면 다시 시도해 주세요.");
        } catch (RuntimeException compensationFailure) {
            log.warn("confirm reservation swap: compensation re-reserve of original seat {} FAILED; "
                            + "user now holds NO seat newSeat={}",
                    request.oldSeatId(), request.newSeatId(), compensationFailure);
            return partialSwapFailure(claimed);
        }
    }

    private LibraryReservationConfirmResponse partialSwapFailure(ActionAudit claimed) {
        actionService.completeAction(claimed, ActionService.OUTCOME_PARTIAL_FAILURE,
                "Swap discharge succeeded but new-seat reserve and original-seat restore both failed.");
        return new LibraryReservationConfirmResponse(
                "FAILED_UPSTREAM",
                null,
                "자리 변경에 실패했고 기존 좌석 복구도 실패했어요. 현재 예약된 좌석이 없으니 다시 예약해 주세요.");
    }

    private LibraryReservationPrepareResponse prepareReserve(String sessionKey, LibraryReservationPrepareRequest request) {
        if (request.seatId() == null) {
            throw new IllegalArgumentException("seatId is required for RESERVE.");
        }
        ActionAudit action = actionService.createPendingAction(
                sessionKey,
                LibraryReservationMcpTool.ACTION_TYPE,
                new LibraryReservationRequest(request.seatId()));
        return new LibraryReservationPrepareResponse(
                action.getId(),
                action.getActionType(),
                "Reserve seat " + request.seatId() + " prepared.",
                action.getCreatedAt().plus(ActionService.ACTION_TTL));
    }

    private LibraryReservationPrepareResponse prepareCancel(String sessionKey, String token) {
        LibraryReservationResult current = reservationConnector.getCurrentCharge(token).orElseThrow(
                () -> new ApiException(ErrorCode.NOT_FOUND));
        ActionAudit action = actionService.createPendingAction(
                sessionKey,
                LibraryCancelMcpTool.ACTION_TYPE,
                new LibraryCancelRequest(current.chargeId(), current.roomId(), current.seatId()));
        return new LibraryReservationPrepareResponse(
                action.getId(),
                action.getActionType(),
                "Cancel charge " + current.chargeId() + " prepared.",
                action.getCreatedAt().plus(ActionService.ACTION_TTL));
    }

    private LibraryReservationPrepareResponse prepareSwap(
            String sessionKey,
            String token,
            LibraryReservationPrepareRequest request) {
        if (request.targetSeatId() == null) {
            throw new IllegalArgumentException("targetSeatId is required for SWAP.");
        }
        LibraryReservationResult current = reservationConnector.getCurrentCharge(token).orElseThrow(
                () -> new ApiException(ErrorCode.NOT_FOUND));
        if (request.seatId() != null && !request.seatId().equals(current.seatId())) {
            throw new IllegalArgumentException("seatId must match the current reserved seat for SWAP.");
        }
        ActionAudit action = actionService.createPendingAction(
                sessionKey,
                LibrarySwapMcpTool.ACTION_TYPE,
                new LibrarySwapRequest(current.chargeId(), request.targetSeatId(), current.roomId(), current.seatId()));
        return new LibraryReservationPrepareResponse(
                action.getId(),
                action.getActionType(),
                "Swap charge " + current.chargeId() + " to seat " + request.targetSeatId() + " prepared.",
                action.getCreatedAt().plus(ActionService.ACTION_TTL));
    }

    private String requireLibrarySession(HttpServletRequest httpRequest) {
        String sessionKey = httpRequest.getSession().getId();
        if (!librarySessionStore.has(sessionKey)) {
            throw new LibraryAuthRequiredException();
        }
        return sessionKey;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private static LibrarySeatPreference preferenceFrom(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return new LibrarySeatPreference(null, null, null, null, null, null);
        }
        Set<String> tags = Arrays.stream(attributes.split("[,\\s]+"))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .map(LibraryReservationWebController::normalizeAttribute)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new LibrarySeatPreference(
                tags.contains("window") ? Boolean.TRUE : null,
                tags.contains("outlet") ? Boolean.TRUE : null,
                tags.contains("standing") ? Boolean.TRUE : null,
                tags.contains("edge") ? Boolean.TRUE : null,
                tags.contains("quiet") ? Boolean.TRUE : null,
                tags.contains("nearEntrance") ? Boolean.TRUE : null);
    }

    private static String normalizeAttribute(String raw) {
        String compact = raw.trim().replace("_", "").replace("-", "");
        if ("nearentrance".equalsIgnoreCase(compact)) {
            return "nearEntrance";
        }
        return compact.toLowerCase(Locale.ROOT);
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

    private void sleepQuietly() {
        try {
            Thread.sleep(reservationIntentPoll.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
