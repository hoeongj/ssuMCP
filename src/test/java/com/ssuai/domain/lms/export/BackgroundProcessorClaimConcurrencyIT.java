package com.ssuai.domain.lms.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentEventType;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentProperties;
import com.ssuai.domain.library.reservation.intent.LibraryReservationOutbox;
import com.ssuai.domain.library.reservation.intent.LibraryReservationOutboxClaimer;
import com.ssuai.domain.library.reservation.intent.LibraryReservationOutboxRepository;
import com.ssuai.support.AbstractPostgresIT;

class BackgroundProcessorClaimConcurrencyIT extends AbstractPostgresIT {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private LibraryReservationOutboxRepository outboxRepository;

    @Autowired
    private LmsExportJobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void cleanTables() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            outboxRepository.deleteAll();
            jobRepository.deleteAll();
        });
    }

    @Test
    void concurrentOutboxClaimersNeverClaimTheSameRow() throws Exception {
        transactions.executeWithoutResult(status -> outboxRepository.save(outbox()));
        LibraryReservationIntentProperties properties = outboxProperties();
        LibraryReservationOutboxClaimer first =
                new LibraryReservationOutboxClaimer(outboxRepository, properties, clock(NOW), "pod-a");
        LibraryReservationOutboxClaimer second =
                new LibraryReservationOutboxClaimer(outboxRepository, properties, clock(NOW), "pod-b");

        List<Long> claimedIds = runConcurrently(
                () -> claimOutbox(first),
                () -> claimOutbox(second));

        assertThat(claimedIds).hasSize(1);
    }

    @Test
    void expiredOutboxLeaseBecomesClaimableAgain() {
        transactions.executeWithoutResult(status -> outboxRepository.save(outbox()));
        LibraryReservationIntentProperties properties = outboxProperties();
        LibraryReservationOutboxClaimer first =
                new LibraryReservationOutboxClaimer(outboxRepository, properties, clock(NOW), "pod-a");
        LibraryReservationOutboxClaimer beforeExpiry =
                new LibraryReservationOutboxClaimer(
                        outboxRepository, properties, clock(NOW.plus(LEASE).minusMillis(1)), "pod-b");
        LibraryReservationOutboxClaimer afterExpiry =
                new LibraryReservationOutboxClaimer(
                        outboxRepository, properties, clock(NOW.plus(LEASE).plusMillis(1)), "pod-b");

        List<LibraryReservationOutbox> firstClaim =
                transactions.execute(status -> first.claimBatch());
        List<LibraryReservationOutbox> earlyClaim =
                transactions.execute(status -> beforeExpiry.claimBatch());
        List<LibraryReservationOutbox> reclaimed =
                transactions.execute(status -> afterExpiry.claimBatch());

        assertThat(firstClaim).hasSize(1);
        assertThat(earlyClaim).isEmpty();
        assertThat(reclaimed).extracting(LibraryReservationOutbox::getId)
                .containsExactly(firstClaim.get(0).getId());
        assertThat(reclaimed.get(0).getClaimedBy()).isEqualTo("pod-b");
    }

    @Test
    void concurrentLmsClaimersNeverClaimTheSameJob() throws Exception {
        transactions.executeWithoutResult(status -> jobRepository.save(job()));
        LmsExportProperties properties = lmsProperties();
        LmsExportJobClaimer first =
                new LmsExportJobClaimer(jobRepository, properties, clock(NOW), "pod-a");
        LmsExportJobClaimer second =
                new LmsExportJobClaimer(jobRepository, properties, clock(NOW), "pod-b");

        List<String> claimedIds = runConcurrently(
                () -> claimJob(first),
                () -> claimJob(second));

        assertThat(claimedIds).hasSize(1);
    }

    @Test
    void expiredLmsLeaseBecomesClaimableAgain() {
        transactions.executeWithoutResult(status -> jobRepository.save(job()));
        LmsExportProperties properties = lmsProperties();
        LmsExportJobClaimer first =
                new LmsExportJobClaimer(jobRepository, properties, clock(NOW), "pod-a");
        LmsExportJobClaimer beforeExpiry =
                new LmsExportJobClaimer(
                        jobRepository, properties, clock(NOW.plus(LEASE).minusMillis(1)), "pod-b");
        LmsExportJobClaimer afterExpiry =
                new LmsExportJobClaimer(
                        jobRepository, properties, clock(NOW.plus(LEASE).plusMillis(1)), "pod-b");

        LmsExportJob firstClaim = transactions.execute(status -> first.claimNextJob().orElseThrow());
        boolean claimedEarly = transactions.execute(status -> beforeExpiry.claimNextJob().isPresent());
        LmsExportJob reclaimed =
                transactions.execute(status -> afterExpiry.claimNextJob().orElseThrow());

        assertThat(claimedEarly).isFalse();
        assertThat(reclaimed.getId()).isEqualTo(firstClaim.getId());
        assertThat(reclaimed.getClaimedBy()).isEqualTo("pod-b");
    }

    private List<Long> claimOutbox(LibraryReservationOutboxClaimer claimer) {
        return transactions.execute(status -> {
            List<Long> ids = claimer.claimBatch().stream()
                    .map(LibraryReservationOutbox::getId)
                    .toList();
            holdClaimLock(ids.isEmpty());
            return ids;
        });
    }

    private List<String> claimJob(LmsExportJobClaimer claimer) {
        return transactions.execute(status -> {
            List<String> ids = claimer.claimNextJob().stream()
                    .map(LmsExportJob::getId)
                    .toList();
            holdClaimLock(ids.isEmpty());
            return ids;
        });
    }

    private static <T> List<T> runConcurrently(
            Callable<List<T>> firstClaim,
            Callable<List<T>> secondClaim) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<List<T>> first = awaitStart(ready, start, firstClaim);
            Callable<List<T>> second = awaitStart(ready, start, secondClaim);
            Future<List<T>> firstResult = executor.submit(first);
            Future<List<T>> secondResult = executor.submit(second);
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            return java.util.stream.Stream.concat(
                            firstResult.get(5, TimeUnit.SECONDS).stream(),
                            secondResult.get(5, TimeUnit.SECONDS).stream())
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> Callable<List<T>> awaitStart(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<List<T>> claim) {
        return () -> {
            ready.countDown();
            start.await(2, TimeUnit.SECONDS);
            return claim.call();
        };
    }

    private static void holdClaimLock(boolean empty) {
        if (empty) {
            return;
        }
        try {
            Thread.sleep(150);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static LibraryReservationIntentProperties outboxProperties() {
        LibraryReservationIntentProperties properties = new LibraryReservationIntentProperties();
        properties.setRelayLease(LEASE);
        properties.setRelayBatchSize(1);
        return properties;
    }

    private static LmsExportProperties lmsProperties() {
        LmsExportProperties properties = new LmsExportProperties();
        properties.setLeaseDuration(LEASE);
        return properties;
    }

    private static Clock clock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static LibraryReservationOutbox outbox() {
        return new LibraryReservationOutbox(
                LibraryReservationIntentEventType.WAIT_REGISTERED,
                1L,
                "{\"intentId\":1}",
                NOW);
    }

    private static LmsExportJob job() {
        return LmsExportJob.createQueued(
                "student",
                "token-hash",
                "{\"selections\":[],\"totalBytes\":0}",
                NOW,
                NOW.plus(Duration.ofMinutes(20)));
    }
}
