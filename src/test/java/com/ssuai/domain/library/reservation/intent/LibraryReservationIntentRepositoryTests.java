package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
class LibraryReservationIntentRepositoryTests {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");

    @Autowired
    private LibraryReservationIntentRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private LibraryReservationWorker worker;

    @MockitoBean
    private LibraryReservationEventRelay eventRelay;

    @Test
    void concurrentClaimersOnlyClaimOneIntent() throws Exception {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> repository.save(waitingIntent()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claim = () -> {
                ready.countDown();
                start.await(2, TimeUnit.SECONDS);
                return template.execute(status -> {
                    List<LibraryReservationIntent> claimed =
                            repository.findClaimableWaitingForUpdate(NOW.plusSeconds(1), 1);
                    if (!claimed.isEmpty()) {
                        claimed.get(0).claimForReservation(NOW.plusSeconds(1), Duration.ofSeconds(30));
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return claimed.size();
                });
            };

            Future<Integer> first = executor.submit(claim);
            Future<Integer> second = executor.submit(claim);
            ready.await(2, TimeUnit.SECONDS);
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void claimQueryIncludesRequestedImmediateReservationIntent() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> repository.save(immediateIntent(3179L, NOW.plus(Duration.ofMinutes(5)))));

        List<LibraryReservationIntent> claimed = template.execute(status ->
                repository.findClaimableWaitingForUpdate(NOW.plusSeconds(1), 10));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).isImmediateReservation()).isTrue();
    }

    @Test
    void activeCompletedImmediateAttemptExistsOnlyUntilIntentExpiry() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            LibraryReservationIntent intent = immediateIntent(9999L, NOW.plus(Duration.ofMinutes(5)));
            intent.claimForReservation(NOW.plusSeconds(1), Duration.ofSeconds(30));
            intent.succeed(NOW.plusSeconds(2), "ok");
            repository.save(intent);
        });

        boolean active = repository.existsActiveCompletedImmediateAttemptForSeat(
                9999L,
                List.of(LibraryReservationIntentStatus.SUCCEEDED, LibraryReservationIntentStatus.FAILED_RACE),
                NOW.plus(Duration.ofMinutes(1)));
        boolean expired = repository.existsActiveCompletedImmediateAttemptForSeat(
                9999L,
                List.of(LibraryReservationIntentStatus.SUCCEEDED, LibraryReservationIntentStatus.FAILED_RACE),
                NOW.plus(Duration.ofMinutes(6)));

        assertThat(active).isTrue();
        assertThat(expired).isFalse();
    }

    private static LibraryReservationIntent waitingIntent() {
        LibraryReservationIntent intent = LibraryReservationIntent.requested(
                "student",
                "session",
                null,
                null,
                null,
                3179L,
                NOW,
                NOW.plus(Duration.ofHours(2)));
        intent.markWaitingForSeat(NOW);
        return intent;
    }

    private static LibraryReservationIntent immediateIntent(Long seatId, Instant expiresAt) {
        return LibraryReservationIntent.immediateReservation(
                "student-" + seatId,
                "session-" + seatId,
                seatId,
                seatId,
                NOW,
                expiresAt);
    }
}
