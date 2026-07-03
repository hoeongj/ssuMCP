package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    // The concurrent single-claimer test moved to LibraryReservationIntentConcurrencyIT
    // (real Postgres via Testcontainers) — H2 cannot reproduce FOR UPDATE SKIP LOCKED
    // faithfully, which made it a CI flake.

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
