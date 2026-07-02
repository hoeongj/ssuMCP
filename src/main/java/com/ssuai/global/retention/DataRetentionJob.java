package com.ssuai.global.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.domain.action.ActionAuditRepository;
import com.ssuai.domain.action.ActionStatus;
import com.ssuai.domain.library.redis.LibrarySchedulerLeadership;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentRepository;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationOutboxRepository;

/**
 * Daily DB retention sweep (ADR 0072, security follow-up #3). Deletes ONLY terminal rows past
 * their configured window — for {@code action_audit} and {@code library_reservation_intents}
 * terminality is the status enum; for {@code library_reservation_outbox} it is
 * {@code published_at IS NOT NULL}. Active rows (PENDING/EXECUTING actions, unpublished outbox
 * events, REQUESTED/WAITING_FOR_SEAT/RESERVING intents) are never deleted regardless of age.
 *
 * <p>Each table is swept in its own {@code REQUIRES_NEW} transaction so one failing table never
 * rolls back or blocks the others, and each sweep is a single bulk DELETE statement. Runs at a
 * quiet hour (04:30 KST) under the shared scheduler leadership lock so at most one pod sweeps
 * (the sweep itself is idempotent — the lock only avoids duplicate work).
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    /** Terminal action_audit lifecycle states (ADR 0015/0055); PENDING/EXECUTING never qualify. */
    static final Set<ActionStatus> ACTION_AUDIT_TERMINAL_STATUSES = Set.of(
            ActionStatus.SUCCESS,
            ActionStatus.FAILED,
            ActionStatus.EXPIRED,
            ActionStatus.SUPERSEDED);

    /** Terminal intent states, mirroring {@code LibraryReservationIntent#TERMINAL_STATUSES}. */
    static final Set<LibraryReservationIntentStatus> INTENT_TERMINAL_STATUSES = Set.of(
            LibraryReservationIntentStatus.SUCCEEDED,
            LibraryReservationIntentStatus.FAILED_RACE,
            LibraryReservationIntentStatus.FAILED_AUTH,
            LibraryReservationIntentStatus.FAILED_UPSTREAM,
            LibraryReservationIntentStatus.CANCELLED,
            LibraryReservationIntentStatus.EXPIRED);

    private final ActionAuditRepository actionAuditRepository;
    private final LibraryReservationOutboxRepository outboxRepository;
    private final LibraryReservationIntentRepository intentRepository;
    private final DataRetentionProperties properties;
    private final LibrarySchedulerLeadership schedulerLeadership;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DataRetentionJob(
            ActionAuditRepository actionAuditRepository,
            LibraryReservationOutboxRepository outboxRepository,
            LibraryReservationIntentRepository intentRepository,
            DataRetentionProperties properties,
            LibrarySchedulerLeadership schedulerLeadership,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.actionAuditRepository = actionAuditRepository;
        this.outboxRepository = outboxRepository;
        this.intentRepository = intentRepository;
        this.properties = properties;
        this.schedulerLeadership = schedulerLeadership;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void cleanUpScheduled() {
        schedulerLeadership.runIfLeader("data-retention", this::cleanUp);
    }

    /** Runs one sweep. Public so tests (and future admin tooling) can trigger it directly. */
    public void cleanUp() {
        if (!properties.isEnabled()) {
            log.debug("data retention disabled; skipping sweep");
            return;
        }
        Instant now = clock.instant();

        sweep("action_audit", cutoff(now, properties.getActionAuditDays()), cutoff ->
                actionAuditRepository.deleteByStatusInAndCreatedAtBefore(ACTION_AUDIT_TERMINAL_STATUSES, cutoff));
        sweep("library_reservation_outbox", cutoff(now, properties.getReservationOutboxDays()), cutoff ->
                outboxRepository.deletePublishedCreatedBefore(cutoff));
        sweep("library_reservation_intents", cutoff(now, properties.getReservationIntentDays()), cutoff ->
                intentRepository.deleteByStatusInAndCreatedAtBefore(INTENT_TERMINAL_STATUSES, cutoff));
    }

    private void sweep(String table, Instant cutoff, TableSweep deleteOperation) {
        try {
            Integer deleted = transactionTemplate.execute(status -> deleteOperation.deleteBefore(cutoff));
            log.info("data retention sweep: table={} cutoff={} deleted={}", table, cutoff, deleted);
        } catch (RuntimeException exception) {
            log.warn("data retention sweep failed: table={} cutoff={}", table, cutoff, exception);
        }
    }

    private static Instant cutoff(Instant now, int days) {
        return now.minus(Duration.ofDays(days));
    }

    @FunctionalInterface
    private interface TableSweep {
        int deleteBefore(Instant cutoff);
    }
}
