package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class LibraryReservationIntentTests {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");

    @Test
    void followsRequestedWaitingReservingSucceededPath() {
        LibraryReservationIntent intent = newIntent();

        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.REQUESTED);

        intent.markWaitingForSeat(NOW);
        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.WAITING_FOR_SEAT);

        intent.claimForReservation(NOW.plusSeconds(1), Duration.ofSeconds(30));
        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.RESERVING);
        assertThat(intent.getLockedUntil()).isEqualTo(NOW.plusSeconds(31));

        intent.succeed(NOW.plusSeconds(2), "ok");
        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.SUCCEEDED);
        assertThat(intent.getOutcomeCode()).isEqualTo("SUCCESS");
        assertThat(intent.getLockedUntil()).isNull();
        assertThat(intent.isTerminal()).isTrue();
    }

    @Test
    void waitingIntentCanReturnWithBackoffAndAttemptIncrement() {
        LibraryReservationIntent intent = newIntent();
        intent.markWaitingForSeat(NOW);
        intent.claimForReservation(NOW, Duration.ofSeconds(30));

        intent.returnToWaiting(NOW.plusSeconds(1), Duration.ofSeconds(30), "no seat");

        assertThat(intent.getStatus()).isEqualTo(LibraryReservationIntentStatus.WAITING_FOR_SEAT);
        assertThat(intent.getAttemptCount()).isEqualTo(1);
        assertThat(intent.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(intent.getOutcomeCode()).isEqualTo("NO_SEAT_AVAILABLE");
    }

    @Test
    void invalidTransitionFailsFast() {
        LibraryReservationIntent intent = newIntent();

        assertThatThrownBy(() -> intent.claimForReservation(NOW, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static LibraryReservationIntent newIntent() {
        return LibraryReservationIntent.requested(
                "student",
                "session",
                null,
                null,
                null,
                3179L,
                NOW,
                NOW.plus(Duration.ofHours(2)));
    }
}
