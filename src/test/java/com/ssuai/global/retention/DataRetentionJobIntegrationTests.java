package com.ssuai.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionAuditRepository;
import com.ssuai.domain.action.ActionStatus;
import com.ssuai.domain.library.redis.LibrarySchedulerLeadership;
import com.ssuai.domain.library.reservation.intent.LibraryReservationEventRelay;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntent;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentEventType;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentRepository;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentStatus;
import com.ssuai.domain.library.reservation.intent.LibraryReservationOutbox;
import com.ssuai.domain.library.reservation.intent.LibraryReservationOutboxRepository;
import com.ssuai.domain.library.reservation.intent.LibraryReservationWorker;

/**
 * Real-schema verification of the retention sweep (ADR 0072): the eligibility predicates are
 * SQL, so they are exercised against a real H2 (PostgreSQL mode) schema built by Flyway — NOT a
 * mocked repository. The safety property under test: age alone NEVER deletes a row — an
 * ancient PENDING/EXECUTING action, unpublished outbox event, or active intent must survive
 * every sweep, while terminal rows past the window disappear and recent terminal rows stay.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
class DataRetentionJobIntegrationTests {

    private static final String ACTION_TYPE = "LIBRARY_SEAT_RESERVATION";
    private static final String PAYLOAD = "{\"seatId\":101}";
    private static final Instant NOW = Instant.now();
    /** Older than every window (audit 180d, outbox/intents 30d). */
    private static final Instant ANCIENT = NOW.minus(Duration.ofDays(200));
    /** Older than the 30d outbox/intent windows but inside the 180d audit window. */
    private static final Instant STALE_30D = NOW.minus(Duration.ofDays(40));
    private static final Instant RECENT = NOW.minus(Duration.ofDays(1));

    @Autowired
    private DataRetentionJob job;

    @Autowired
    private DataRetentionProperties properties;

    @Autowired
    private ActionAuditRepository actionAuditRepository;

    @Autowired
    private LibraryReservationOutboxRepository outboxRepository;

    @Autowired
    private LibraryReservationIntentRepository intentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private LibraryReservationWorker worker;

    @MockitoBean
    private LibraryReservationEventRelay eventRelay;

    @Test
    void deletesOnlyOldTerminalActionAuditRows() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long oldSuccess = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.SUCCESS, ANCIENT)).getId());
        Long oldFailed = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.FAILED, ANCIENT)).getId());
        Long oldExpired = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.EXPIRED, ANCIENT)).getId());
        Long oldSuperseded = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.SUPERSEDED, ANCIENT)).getId());
        // Non-terminal rows: age alone must never delete them.
        Long oldPending = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.PENDING, ANCIENT)).getId());
        Long oldExecuting = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.EXECUTING, ANCIENT)).getId());
        // Terminal but inside the 180-day window.
        Long recentSuccess = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.SUCCESS, RECENT)).getId());

        job.cleanUp();

        assertThat(actionAuditRepository.findById(oldSuccess)).isEmpty();
        assertThat(actionAuditRepository.findById(oldFailed)).isEmpty();
        assertThat(actionAuditRepository.findById(oldExpired)).isEmpty();
        assertThat(actionAuditRepository.findById(oldSuperseded)).isEmpty();
        assertThat(actionAuditRepository.findById(oldPending)).isPresent();
        assertThat(actionAuditRepository.findById(oldExecuting)).isPresent();
        assertThat(actionAuditRepository.findById(recentSuccess)).isPresent();
    }

    @Test
    void deletesOnlyOldPublishedOutboxRows() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long oldPublished = template.execute(s -> outboxRepository.save(outbox(STALE_30D, true)).getId());
        // Never relayed: must survive regardless of age so the relay can still deliver it.
        Long oldUnpublished = template.execute(s -> outboxRepository.save(outbox(STALE_30D, false)).getId());
        Long recentPublished = template.execute(s -> outboxRepository.save(outbox(RECENT, true)).getId());

        job.cleanUp();

        assertThat(outboxRepository.findById(oldPublished)).isEmpty();
        assertThat(outboxRepository.findById(oldUnpublished)).isPresent();
        assertThat(outboxRepository.findById(recentPublished)).isPresent();
    }

    @Test
    void deletesOnlyOldTerminalIntentRows() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long oldSucceeded = template.execute(s -> intentRepository.save(succeededIntent(STALE_30D)).getId());
        Long oldCancelled = template.execute(s -> intentRepository.save(cancelledIntent(STALE_30D)).getId());
        Long oldExpired = template.execute(s -> intentRepository.save(expiredIntent(STALE_30D)).getId());
        // Active rows: age alone must never delete them.
        Long oldWaiting = template.execute(s -> intentRepository.save(waitingIntent(STALE_30D)).getId());
        Long oldReserving = template.execute(s -> intentRepository.save(reservingIntent(STALE_30D)).getId());
        Long recentSucceeded = template.execute(s -> intentRepository.save(succeededIntent(RECENT)).getId());

        job.cleanUp();

        assertThat(intentRepository.findById(oldSucceeded)).isEmpty();
        assertThat(intentRepository.findById(oldCancelled)).isEmpty();
        assertThat(intentRepository.findById(oldExpired)).isEmpty();
        assertThat(intentRepository.findById(oldWaiting).orElseThrow().getStatus())
                .isEqualTo(LibraryReservationIntentStatus.WAITING_FOR_SEAT);
        assertThat(intentRepository.findById(oldReserving)).isPresent();
        assertThat(intentRepository.findById(recentSucceeded)).isPresent();
    }

    @Test
    void disabledFlagSkipsEverySweep() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long oldSuccess = template.execute(s -> actionAuditRepository.save(audit(ActionStatus.SUCCESS, ANCIENT)).getId());
        Long oldPublished = template.execute(s -> outboxRepository.save(outbox(STALE_30D, true)).getId());
        Long oldSucceeded = template.execute(s -> intentRepository.save(succeededIntent(STALE_30D)).getId());

        DataRetentionProperties disabled = new DataRetentionProperties();
        disabled.setEnabled(false);
        DataRetentionJob disabledJob = new DataRetentionJob(
                actionAuditRepository,
                outboxRepository,
                intentRepository,
                disabled,
                LibrarySchedulerLeadership.noop(),
                transactionManager,
                Clock.systemUTC());

        disabledJob.cleanUp();

        assertThat(actionAuditRepository.findById(oldSuccess)).isPresent();
        assertThat(outboxRepository.findById(oldPublished)).isPresent();
        assertThat(intentRepository.findById(oldSucceeded)).isPresent();
    }

    @Test
    void retentionDefaultsMatchAdr0072() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getActionAuditDays()).isEqualTo(180);
        assertThat(properties.getReservationOutboxDays()).isEqualTo(30);
        assertThat(properties.getReservationIntentDays()).isEqualTo(30);
    }

    private static ActionAudit audit(ActionStatus status, Instant createdAt) {
        ActionAudit audit = ActionAudit.pending(owner(status, createdAt), ACTION_TYPE, PAYLOAD, createdAt);
        switch (status) {
            case PENDING -> { /* stays PENDING */ }
            case EXECUTING -> audit.markExecuting(createdAt);
            case SUCCESS -> {
                audit.markExecuting(createdAt);
                audit.complete("SUCCESS", "ok", createdAt);
            }
            case FAILED -> {
                audit.markExecuting(createdAt);
                audit.complete("FAILURE_UPSTREAM", "boom", createdAt);
            }
            case EXPIRED -> audit.expire(createdAt);
            case SUPERSEDED -> audit.supersede(createdAt);
        }
        return audit;
    }

    private static LibraryReservationOutbox outbox(Instant createdAt, boolean published) {
        LibraryReservationOutbox row = new LibraryReservationOutbox(
                LibraryReservationIntentEventType.WAIT_REGISTERED, 999_999L, "{\"intent\":\"retention-test\"}", createdAt);
        if (published) {
            row.markPublished(createdAt);
        }
        return row;
    }

    private static LibraryReservationIntent succeededIntent(Instant createdAt) {
        LibraryReservationIntent intent = requestedIntent(createdAt);
        intent.markWaitingForSeat(createdAt);
        intent.claimForReservation(createdAt, Duration.ofSeconds(30));
        intent.succeed(createdAt, "ok");
        return intent;
    }

    private static LibraryReservationIntent cancelledIntent(Instant createdAt) {
        LibraryReservationIntent intent = requestedIntent(createdAt);
        intent.cancel(createdAt, "cancelled by test");
        return intent;
    }

    private static LibraryReservationIntent expiredIntent(Instant createdAt) {
        LibraryReservationIntent intent = requestedIntent(createdAt);
        intent.expire(createdAt, "expired by test");
        return intent;
    }

    private static LibraryReservationIntent waitingIntent(Instant createdAt) {
        LibraryReservationIntent intent = requestedIntent(createdAt);
        intent.markWaitingForSeat(createdAt);
        return intent;
    }

    private static LibraryReservationIntent reservingIntent(Instant createdAt) {
        LibraryReservationIntent intent = waitingIntent(createdAt);
        intent.claimForReservation(createdAt, Duration.ofSeconds(30));
        return intent;
    }

    private static LibraryReservationIntent requestedIntent(Instant createdAt) {
        return LibraryReservationIntent.requested(
                owner(null, createdAt),
                "retention-session-key",
                null,
                null,
                null,
                null,
                createdAt,
                createdAt.plus(Duration.ofHours(2)));
    }

    private static String owner(Object discriminator, Instant createdAt) {
        return "retention-" + (discriminator == null ? "intent" : discriminator) + "-" + createdAt.toEpochMilli();
    }
}
