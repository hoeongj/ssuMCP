package com.ssuai.domain.action;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionService {

    public static final Duration ACTION_TTL = Duration.ofMinutes(5);

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE_RACE = "FAILURE_RACE";
    public static final String OUTCOME_FAILURE_AUTH = "FAILURE_AUTH";
    public static final String OUTCOME_FAILURE_UPSTREAM = "FAILURE_UPSTREAM";
    public static final String OUTCOME_TIMEOUT = "TIMEOUT";

    private final ActionAuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Autowired
    public ActionService(ActionAuditRepository repository, ObjectMapper objectMapper, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    ActionService(ActionAuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meterRegistry = new SimpleMeterRegistry();
    }

    /**
     * Prepares a new PENDING action for {@code studentId}. Before inserting it, every prior
     * still-PENDING action of the same owner is atomically marked SUPERSEDED (ADR 0055) so a
     * later confirm can never execute a stale request the user did not re-approve. After this
     * call the owner has exactly one active PENDING action: the one returned here.
     */
    @Transactional
    public ActionAudit createPendingAction(String studentId, String actionType, Object payload) {
        String serialized = serialize(payload);
        Instant now = clock.instant();
        int superseded = repository.markPendingSuperseded(studentId, now);
        if (superseded > 0) {
            meterRegistry.counter("library.action", "action_type", actionType, "status", "superseded")
                    .increment(superseded);
        }
        ActionAudit action = ActionAudit.pending(studentId, actionType, serialized, now);
        ActionAudit saved = repository.save(action);
        count(actionType, "prepared");
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<ActionAudit> findPendingAction(String studentId) {
        return repository.findTopByStudentIdAndStatusOrderByCreatedAtDesc(studentId, ActionStatus.PENDING);
    }

    /**
     * Returns all still-PENDING actions of {@code studentId}. Post-supersede a well-behaved
     * owner has 0 or 1; more than one is only reachable through a concurrent-prepare race and
     * is the signal the no-id confirm path uses to refuse rather than guess which to execute.
     */
    @Transactional(readOnly = true)
    public List<ActionAudit> findActivePendingActions(String studentId) {
        return repository.findAllByStudentIdAndStatus(studentId, ActionStatus.PENDING);
    }

    /**
     * Atomically claims the most recent PENDING action and moves it to EXECUTING under a
     * row lock (SELECT ... FOR UPDATE). Two concurrent confirms can never both execute the
     * same action: the loser finds no PENDING row. The audit row is persisted as EXECUTING
     * <em>before</em> the upstream call, so a crash mid-call is still reconstructable.
     *
     * @throws NoPendingActionException if there is no PENDING action to claim
     * @throws ActionExpiredException   if the latest PENDING action is past its TTL (it is
     *                                  marked EXPIRED before throwing)
     */
    @Transactional
    public ActionAudit claimPendingAction(String studentId) {
        ActionAudit action = repository
                .lockByStudentIdAndStatus(studentId, ActionStatus.PENDING, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(NoPendingActionException::new);
        Instant now = clock.instant();
        if (isExpired(action, now)) {
            action.expire(now);
            repository.save(action);
            count(action.getActionType(), "expired");
            throw new ActionExpiredException();
        }
        action.markExecuting(now);
        ActionAudit saved = repository.save(action);
        count(action.getActionType(), "executing");
        return saved;
    }

    /**
     * Ownership-enforced explicit-id claim. Locks and moves to EXECUTING the action with the
     * given {@code actionId} <em>only</em> when it belongs to {@code studentId} and is still
     * PENDING and not past its TTL. There is deliberately no fallback to "confirm the latest
     * action": a wrong owner, an unknown id, or an already-executed / superseded / expired row
     * raises an exception so confirm_action can surface a clear error and never execute a
     * different action than the one the caller targeted.
     *
     * @throws NoPendingActionException if no PENDING action with that id is owned by the caller
     * @throws ActionExpiredException   if the targeted action is owned and PENDING but past TTL
     *                                  (it is marked EXPIRED before throwing)
     */
    @Transactional
    public ActionAudit claimPendingActionById(String studentId, Long actionId) {
        ActionAudit action = repository
                .lockByIdAndStudentIdAndStatus(actionId, studentId, ActionStatus.PENDING, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(NoPendingActionException::new);
        Instant now = clock.instant();
        if (isExpired(action, now)) {
            action.expire(now);
            repository.save(action);
            count(action.getActionType(), "expired");
            throw new ActionExpiredException();
        }
        action.markExecuting(now);
        ActionAudit saved = repository.save(action);
        count(action.getActionType(), "executing");
        return saved;
    }

    /**
     * Records the terminal outcome of an EXECUTING action. {@code outcomeCode} is one of the
     * {@code OUTCOME_*} constants; SUCCESS maps to status SUCCESS, anything else to FAILED.
     * This is the only place an action becomes terminal after its upstream call, so the audit
     * never reports success for a call that actually failed.
     */
    @Transactional
    public ActionAudit completeAction(ActionAudit action, String outcomeCode, String outcomeMessage) {
        action.complete(outcomeCode, outcomeMessage, clock.instant());
        ActionAudit saved = repository.save(action);
        meterRegistry.counter("library.action",
                        "action_type", action.getActionType(),
                        "status", OUTCOME_SUCCESS.equals(outcomeCode) ? "success" : "failed",
                        "outcome", outcomeCode)
                .increment();
        return saved;
    }

    /**
     * Idempotently finalizes the {@link ActionAudit} an async reservation intent is linked to
     * (via {@code library_reservation_intents.action_audit_id}). The reservation worker is the
     * single source of truth for the terminal outcome of an intent-queue reservation: when the
     * worker drives the intent to a terminal state it calls this to mirror that outcome onto the
     * audit. Because the synchronous confirm path leaves a timed-out reservation audit in
     * EXECUTING (no longer terminally failing it), this is the call that closes the audit out.
     *
     * <p>Idempotent and safe to call more than once or after a terminal state:
     * <ul>
     *   <li>{@code actionAuditId == null} (a non-immediate wait intent) — no-op;</li>
     *   <li>row not found — no-op;</li>
     *   <li>row already terminal (SUCCESS / FAILED / EXPIRED / SUPERSEDED) or never claimed
     *       (still PENDING) — no-op, never overwriting an already-decided audit;</li>
     *   <li>only an EXECUTING row is completed, exactly once.</li>
     * </ul>
     * This is what prevents the money-transaction-style double-state accident where the API
     * reported a timeout/failure but the seat was actually reserved.
     */
    @Transactional
    public void finalizeFromIntent(Long actionAuditId, String outcomeCode, String outcomeMessage) {
        if (actionAuditId == null) {
            return;
        }
        repository.findById(actionAuditId).ifPresent(action -> {
            if (action.getStatus() != ActionStatus.EXECUTING) {
                // Already terminal (the sync path completed it before timing out, or a prior
                // worker finalize already ran), still PENDING, or expired/superseded: never flip.
                return;
            }
            completeAction(action, outcomeCode, outcomeMessage);
        });
    }

    /** Marks the latest PENDING action EXPIRED if it is past its TTL. No-op otherwise. */
    @Transactional
    public void expirePending(String studentId) {
        findPendingAction(studentId).ifPresent(action -> {
            Instant now = clock.instant();
            if (isExpired(action, now)) {
                action.expire(now);
                repository.save(action);
                count(action.getActionType(), "expired");
            }
        });
    }

    @Transactional
    @Scheduled(fixedDelay = 60_000)
    public void expireStaleActions() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(ACTION_TTL);
        List<ActionAudit> staleActions =
                repository.findAllByStatusAndCreatedAtBefore(ActionStatus.PENDING, cutoff);
        if (staleActions.isEmpty()) {
            return;
        }
        staleActions.forEach(action -> action.expire(now));
        repository.saveAll(staleActions);
        meterRegistry.counter("library.action", "action_type", "mixed", "status", "expired")
                .increment(staleActions.size());
    }

    public boolean isExpired(ActionAudit action) {
        return isExpired(action, clock.instant());
    }

    public <T> T payload(ActionAudit action, Class<T> payloadType) {
        try {
            return objectMapper.readValue(action.getPayload(), payloadType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Action payload cannot be parsed.", exception);
        }
    }

    private boolean isExpired(ActionAudit action, Instant now) {
        return action.getCreatedAt().plus(ACTION_TTL).isBefore(now);
    }

    private void count(String actionType, String status) {
        meterRegistry.counter("library.action", "action_type", actionType, "status", status).increment();
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Action payload cannot be serialized.", exception);
        }
    }

    public static class NoPendingActionException extends RuntimeException {
    }

    public static class ActionExpiredException extends RuntimeException {
    }
}
