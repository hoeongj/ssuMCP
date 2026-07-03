package com.ssuai.domain.library.reservation.intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.events.LibrarySeatEventPublisher;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.redis.LibraryDistributedLockClient;
import com.ssuai.domain.library.redis.LibraryRedisMetrics;
import com.ssuai.domain.library.redis.LibraryRedisProperties;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

@Component
public class LibraryReservationWorker {

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationWorker.class);

    private final LibraryReservationIntentTransactions transactions;
    private final LibraryReservationSeatSelector seatSelector;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;
    private final LibrarySeatEventPublisher seatEventPublisher;
    private final LibraryDistributedLockClient lockClient;
    private final LibraryRedisMetrics redisMetrics;
    private final LibraryRedisProperties redisProperties;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public LibraryReservationWorker(
            LibraryReservationIntentTransactions transactions,
            LibraryReservationSeatSelector seatSelector,
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector,
            LibrarySeatEventPublisher seatEventPublisher,
            LibraryDistributedLockClient lockClient,
            LibraryRedisMetrics redisMetrics,
            LibraryRedisProperties redisProperties) {
        this.transactions = transactions;
        this.seatSelector = seatSelector;
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
        this.seatEventPublisher = seatEventPublisher;
        this.lockClient = lockClient;
        this.redisMetrics = redisMetrics;
        this.redisProperties = redisProperties;
    }

    @Scheduled(fixedDelayString = "#{@libraryReservationIntentProperties.pollInterval.toMillis()}")
    public void poll() {
        runOnce();
    }

    public void wake() {
        runOnce();
    }

    private void runOnce() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            pollInternal();
        } finally {
            polling.set(false);
        }
    }

    private void pollInternal() {
        transactions.expireWaiting();
        recoverExpiredLeases();

        List<LibraryReservationIntent> claimed = transactions.claimWaitingBatch();
        if (claimed.isEmpty()) {
            return;
        }

        Map<Long, List<ReadyIntent>> bySeat = new LinkedHashMap<>();
        for (LibraryReservationIntent intent : claimed) {
            Optional<ReadyIntent> ready = prepare(intent);
            ready.ifPresent(value -> bySeat.computeIfAbsent(value.seatId(), ignored -> new ArrayList<>()).add(value));
        }
        bySeat.forEach(this::processSeatGroup);
    }

    private Optional<ReadyIntent> prepare(LibraryReservationIntent intent) {
        String token = sessionStore.token(intent.getSessionKey()).orElse(null);
        if (token == null) {
            transactions.failAuth(intent.getId(), "Library session token is missing or expired.");
            return Optional.empty();
        }
        try {
            if (intent.isImmediateReservation()) {
                return Optional.of(new ReadyIntent(intent.getId(), token, intent.getTargetSeatId()));
            }
            Optional<LibraryReservationResult> current;
            try {
                current = reservationConnector.getCurrentCharge(token);
            } catch (LibraryAuthRequiredException exception) {
                transactions.failAuth(intent.getId(), "Library session token is missing or expired.");
                return Optional.empty();
            } catch (RuntimeException exception) {
                log.warn("library reservation intent current charge check failed; proceeding with seat scan: intentId={}",
                        intent.getId(), exception);
                current = Optional.empty();
            }
            if (current.isPresent()) {
                LibraryReservationResult result = current.get();
                Long existingSeatId = result.seatId() == null ? intent.getTargetSeatId() : result.seatId();
                transactions.succeed(
                        intent.getId(),
                        existingSeatId,
                        "User already holds a library seat; skipping auto-reserve to avoid double-booking. "
                                + successMessage(result));
                return Optional.empty();
            }
            Optional<Long> seatId = seatSelector.findAvailableSeat(intent);
            if (seatId.isEmpty()) {
                transactions.returnToWaiting(intent.getId());
                return Optional.empty();
            }
            return Optional.of(new ReadyIntent(intent.getId(), token, seatId.get()));
        } catch (LibraryAuthRequiredException exception) {
            transactions.failAuth(intent.getId(), "Library session token is missing or expired.");
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("library reservation intent seat scan failed: intentId={}", intent.getId(), exception);
            transactions.failUpstream(intent.getId(), "Unable to read current library seat availability.");
            return Optional.empty();
        }
    }

    private void processSeatGroup(Long seatId, List<ReadyIntent> intents) {
        if (transactions.hasActiveCompletedImmediateAttemptForSeat(seatId)) {
            intents.forEach(intent -> transactions.failRace(
                    intent.intentId(),
                    seatId,
                    "Another recent immediate reservation intent already resolved this seat."));
            return;
        }
        ReadyIntent winner = intents.get(0);
        for (int index = 1; index < intents.size(); index++) {
            transactions.failRace(
                    intents.get(index).intentId(),
                    seatId,
                    "Another local wait intent already attempted this seat in the same worker tick.");
        }
        executeWithSeatLock(winner, seatId);
    }

    private void executeWithSeatLock(ReadyIntent intent, Long seatId) {
        String lockName = redisProperties.seatLockName(seatId);
        Optional<LibraryDistributedLockClient.LockLease> lease;
        try {
            lease = lockClient.tryAcquire(lockName, redisProperties.getSeatLockWaitTime());
        } catch (InterruptedException exception) {
            // Fail-CLOSED: reserving without the lock would defeat the distributed
            // lock and risk a double-booking under contention / multi-pod. Treat a lock-acquire
            // interrupt as a transient "couldn't acquire" and defer the intent for a later retry
            // via the existing backoff machinery — NEVER reserve lock-less.
            Thread.currentThread().interrupt();
            log.warn("seat lock interrupted; deferring reservation for retry: seatId={}", seatId);
            redisMetrics.countSeatLock("deferred");
            redisMetrics.countFailure("seat_lock_acquire", exception);
            deferForRetry(intent, seatId, "Seat lock acquisition was interrupted; retrying later.");
            return;
        } catch (RuntimeException exception) {
            // Fail-CLOSED: same reasoning as the interrupt branch — a Redis/lock
            // failure must not let the worker reserve without holding the lock.
            log.warn("seat lock unavailable; deferring reservation for retry: seatId={}", seatId, exception);
            redisMetrics.countSeatLock("deferred");
            redisMetrics.countFailure("seat_lock_acquire", exception);
            deferForRetry(intent, seatId, "Seat lock was unavailable; retrying later.");
            return;
        }
        if (lease.isEmpty()) {
            // Could not acquire within the wait window: another pod currently holds the seat lock.
            // Defer for a later retry (consistent fail-closed handling of every "couldn't acquire"
            // outcome) rather than terminally failing — the next tick re-targets the same seat and
            // resolves cleanly (upstream "taken" → FAILED_RACE) or succeeds once the lock frees.
            redisMetrics.countSeatLock("skipped");
            deferForRetry(intent, seatId, "Another pod holds the seat reservation lock; retrying later.");
            return;
        }
        redisMetrics.countSeatLock("acquired");
        try {
            executeReservation(intent, seatId);
        } finally {
            try {
                lease.get().close();
            } catch (RuntimeException exception) {
                log.warn("seat lock release failed: seatId={}", seatId, exception);
                redisMetrics.countFailure("seat_lock_release", exception);
            }
        }
    }

    /**
     * Leaves the intent for a later attempt using the worker's existing retry machinery
     * (attemptCount / nextAttemptAt backoff → WAITING_FOR_SEAT), exactly as a normal
     * "no seat available yet" outcome. Used for fail-closed handling of any seat-lock
     * acquisition failure so a transient lock problem never results in a lock-less reservation.
     */
    private void deferForRetry(ReadyIntent intent, Long seatId, String reason) {
        log.debug("deferring reservation intent for retry: intentId={} seatId={} reason={}",
                intent.intentId(), seatId, reason);
        transactions.returnToWaiting(intent.intentId());
    }

    private void executeReservation(ReadyIntent intent, Long seatId) {
        try {
            LibraryReservationResult result = reservationConnector.reserve(
                    intent.token(), new LibraryReservationRequest(seatId));
            transactions.succeed(intent.intentId(), seatId, successMessage(result));
            seatEventPublisher.reserve(result.roomId(), result.seatId() == null ? seatId : result.seatId());
        } catch (LibrarySeatNotAvailableException exception) {
            transactions.failRace(intent.intentId(), seatId, "Seat was already taken upstream.");
        } catch (LibraryAuthRequiredException exception) {
            transactions.failAuth(intent.intentId(), "Library session was rejected by upstream.");
        } catch (ConnectorTimeoutException exception) {
            transactions.failUpstream(intent.intentId(), "Library reservation upstream timed out.");
        } catch (RuntimeException exception) {
            log.warn("library reservation intent reserve failed: intentId={} seatId={}",
                    intent.intentId(), seatId, exception);
            transactions.failUpstream(intent.intentId(), "Library reservation upstream failed.");
        }
    }

    private void recoverExpiredLeases() {
        List<LibraryReservationIntent> expiredLeases = transactions.claimExpiredLeases();
        for (LibraryReservationIntent intent : expiredLeases) {
            String token = sessionStore.token(intent.getSessionKey()).orElse(null);
            if (token == null) {
                transactions.failAuth(intent.getId(), "Library session token is missing while recovering an expired lease.");
                continue;
            }
            try {
                Optional<LibraryReservationResult> current = reservationConnector.getCurrentCharge(token);
                if (current.isPresent()) {
                    transactions.succeed(
                            intent.getId(),
                            intent.getTargetSeatId(),
                            "Recovered reservation after worker lease expiry: " + successMessage(current.get()));
                    LibraryReservationResult result = current.get();
                    seatEventPublisher.reserve(
                            result.roomId(),
                            result.seatId() == null ? intent.getTargetSeatId() : result.seatId());
                } else {
                    transactions.failUpstream(
                            intent.getId(),
                            "Worker lease expired and current charge did not confirm a reservation.");
                }
            } catch (LibraryAuthRequiredException exception) {
                transactions.failAuth(intent.getId(), "Library session was rejected while recovering an expired lease.");
            } catch (RuntimeException exception) {
                log.warn("library reservation intent reaper failed: intentId={}", intent.getId(), exception);
                transactions.failUpstream(intent.getId(), "Unable to verify reservation after worker lease expiry.");
            }
        }
    }

    private static String successMessage(LibraryReservationResult result) {
        return "%s %s reserved, chargeId=%d, time=%s~%s".formatted(
                result.roomName(),
                result.seatCode(),
                result.chargeId(),
                result.beginTime(),
                result.endTime());
    }

    private record ReadyIntent(Long intentId, String token, Long seatId) {
    }
}
