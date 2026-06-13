package com.ssuai.domain.library.reservation.intent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LibraryReservationIntentTransactions {

    private static final Set<LibraryReservationIntentStatus> COMPLETED_SEAT_ATTEMPT_STATUSES = Set.of(
            LibraryReservationIntentStatus.SUCCEEDED,
            LibraryReservationIntentStatus.FAILED_RACE);

    private final LibraryReservationIntentRepository intentRepository;
    private final LibraryReservationOutboxRepository outboxRepository;
    private final LibraryReservationIntentProperties properties;
    private final LibraryReservationIntentMetrics metrics;
    private final LibraryReservationIntentWakeNotifier wakeNotifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LibraryReservationIntentTransactions(
            LibraryReservationIntentRepository intentRepository,
            LibraryReservationOutboxRepository outboxRepository,
            LibraryReservationIntentProperties properties,
            LibraryReservationIntentMetrics metrics,
            LibraryReservationIntentWakeNotifier wakeNotifier,
            ObjectMapper objectMapper,
            Clock clock) {
        this.intentRepository = intentRepository;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.wakeNotifier = wakeNotifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public LibraryReservationRegistrationResult registerWait(String sessionKey, LibraryReservationWaitRequest request) {
        String studentId = sessionKey;
        Optional<LibraryReservationIntent> active =
                intentRepository.findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
                        studentId, LibraryReservationIntentMetrics.ACTIVE_STATUSES);
        if (active.isPresent()) {
            return new LibraryReservationRegistrationResult(LibraryReservationIntentView.from(active.get()), false);
        }

        Instant now = clock.instant();
        Duration expiryDuration = request.expiresIn() == null ? properties.getDefaultExpiry() : request.expiresIn();
        LibraryReservationIntent intent = LibraryReservationIntent.requested(
                studentId,
                sessionKey,
                request.preferredFloor(),
                request.preferredRoomIds(),
                request.seatAttributes(),
                request.targetSeatId(),
                now,
                now.plus(expiryDuration));
        intent.markWaitingForSeat(now);
        LibraryReservationIntent saved = intentRepository.save(intent);
        append(saved, LibraryReservationIntentEventType.WAIT_REGISTERED, "Wait registered");
        metrics.countTransition(saved.getStatus(), saved.getOutcomeCode());
        notifyReadyAfterCommit(saved.getId());
        return new LibraryReservationRegistrationResult(LibraryReservationIntentView.from(saved), true);
    }

    @Transactional
    public LibraryReservationIntentView createImmediateReservation(
            String sessionKey,
            Long actionAuditId,
            Long targetSeatId,
            Duration expiresIn) {
        Instant now = clock.instant();
        Duration expiryDuration = expiresIn == null ? properties.getDefaultExpiry() : expiresIn;
        LibraryReservationIntent intent = LibraryReservationIntent.immediateReservation(
                sessionKey,
                sessionKey,
                targetSeatId,
                actionAuditId,
                now,
                now.plus(expiryDuration));
        LibraryReservationIntent saved = intentRepository.save(intent);
        append(saved, LibraryReservationIntentEventType.WAIT_REGISTERED, "Immediate reservation intent created");
        metrics.countTransition(saved.getStatus(), saved.getOutcomeCode());
        notifyReadyAfterCommit(saved.getId());
        return LibraryReservationIntentView.from(saved);
    }

    @Transactional(readOnly = true)
    public Optional<LibraryReservationIntentView> latestForSession(String sessionKey) {
        return intentRepository.findTopByStudentIdOrderByCreatedAtDesc(sessionKey)
                .map(LibraryReservationIntentView::from);
    }

    @Transactional(readOnly = true)
    public Optional<LibraryReservationIntentView> findById(Long intentId) {
        return intentRepository.findSnapshotById(intentId)
                .map(LibraryReservationIntentView::from);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveCompletedImmediateAttemptForSeat(Long seatId) {
        return intentRepository.existsActiveCompletedImmediateAttemptForSeat(
                seatId,
                COMPLETED_SEAT_ATTEMPT_STATUSES,
                clock.instant());
    }

    @Transactional
    public Optional<LibraryReservationIntentView> cancelActive(String sessionKey) {
        Optional<LibraryReservationIntent> active =
                intentRepository.findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
                        sessionKey, LibraryReservationIntentMetrics.ACTIVE_STATUSES);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        LibraryReservationIntent intent = active.get();
        if (intent.getStatus() == LibraryReservationIntentStatus.RESERVING) {
            return Optional.of(LibraryReservationIntentView.from(intent));
        }
        intent.cancel(clock.instant(), "User cancelled the wait intent.");
        append(intent, LibraryReservationIntentEventType.CANCELLED, intent.getOutcomeMessage());
        metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        return Optional.of(LibraryReservationIntentView.from(intent));
    }

    @Transactional
    public List<LibraryReservationIntent> claimWaitingBatch() {
        Instant now = clock.instant();
        List<LibraryReservationIntent> claimable =
                intentRepository.findClaimableWaitingForUpdate(now, properties.getBatchSize());
        claimable.forEach(intent -> intent.claimForReservation(now, properties.getLeaseSeconds()));
        return claimable;
    }

    @Transactional
    public List<LibraryReservationIntent> claimExpiredLeases() {
        Instant now = clock.instant();
        List<LibraryReservationIntent> expired =
                intentRepository.findExpiredLeasesForUpdate(now, properties.getBatchSize());
        expired.forEach(intent -> intent.extendLeaseForReaper(now, properties.getLeaseSeconds()));
        return expired;
    }

    @Transactional
    public int expireWaiting() {
        Instant now = clock.instant();
        List<LibraryReservationIntent> expired =
                intentRepository.findExpiredWaitingForUpdate(now, properties.getBatchSize());
        expired.forEach(intent -> {
            intent.expire(now, "Wait intent expired before a matching seat was reserved.");
            append(intent, LibraryReservationIntentEventType.EXPIRED, intent.getOutcomeMessage());
            metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        });
        return expired.size();
    }

    @Transactional
    public LibraryReservationIntentView returnToWaiting(Long intentId) {
        LibraryReservationIntent intent = lock(intentId);
        Instant now = clock.instant();
        Duration backoff = properties.backoffForAttempt(intent.getAttemptCount() + 1);
        intent.returnToWaiting(now, backoff, "No matching seat is available yet.");
        metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        return LibraryReservationIntentView.from(intent);
    }

    @Transactional
    public LibraryReservationIntentView succeed(Long intentId, Long seatId, String message) {
        LibraryReservationIntent intent = lock(intentId);
        intent.succeed(clock.instant(), message);
        append(intent, LibraryReservationIntentEventType.SEAT_FOUND, "Seat " + seatId + " was claimable.");
        append(intent, LibraryReservationIntentEventType.RESERVATION_SUCCEEDED, message);
        metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        return LibraryReservationIntentView.from(intent);
    }

    @Transactional
    public LibraryReservationIntentView failRace(Long intentId, Long seatId, String message) {
        LibraryReservationIntent intent = lock(intentId);
        intent.failRace(clock.instant(), message);
        append(intent, LibraryReservationIntentEventType.SEAT_FOUND, "Seat " + seatId + " was claimable.");
        append(intent, LibraryReservationIntentEventType.RESERVATION_FAILED, message);
        metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        return LibraryReservationIntentView.from(intent);
    }

    @Transactional
    public LibraryReservationIntentView failAuth(Long intentId, String message) {
        LibraryReservationIntent intent = lock(intentId);
        intent.failAuth(clock.instant(), message);
        append(intent, LibraryReservationIntentEventType.RESERVATION_FAILED, message);
        metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        return LibraryReservationIntentView.from(intent);
    }

    @Transactional
    public LibraryReservationIntentView failUpstream(Long intentId, String message) {
        LibraryReservationIntent intent = lock(intentId);
        intent.failUpstream(clock.instant(), message);
        append(intent, LibraryReservationIntentEventType.RESERVATION_FAILED, message);
        metrics.countTransition(intent.getStatus(), intent.getOutcomeCode());
        return LibraryReservationIntentView.from(intent);
    }

    private LibraryReservationIntent lock(Long intentId) {
        return intentRepository.findByIdForUpdate(intentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reservation intent: " + intentId));
    }

    private void notifyReadyAfterCommit(Long intentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wakeNotifier.notifyIntentReady(intentId);
                }
            });
            return;
        }
        wakeNotifier.notifyIntentReady(intentId);
    }

    private void append(
            LibraryReservationIntent intent,
            LibraryReservationIntentEventType eventType,
            String message) {
        outboxRepository.save(new LibraryReservationOutbox(
                eventType,
                intent.getId(),
                payload(intent, eventType, message),
                clock.instant()));
    }

    private String payload(
            LibraryReservationIntent intent,
            LibraryReservationIntentEventType eventType,
            String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("intentId", intent.getId());
        payload.put("status", intent.getStatus().name());
        payload.put("targetSeatId", intent.getTargetSeatId());
        payload.put("attemptCount", intent.getAttemptCount());
        payload.put("outcomeCode", intent.getOutcomeCode());
        payload.put("outcomeMessage", message);
        payload.put("createdAt", clock.instant().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize reservation outbox payload.", exception);
        }
    }
}
