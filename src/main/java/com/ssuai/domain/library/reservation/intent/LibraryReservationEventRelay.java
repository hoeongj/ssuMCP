package com.ssuai.domain.library.reservation.intent;

import java.time.Clock;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LibraryReservationEventRelay {

    private final LibraryReservationOutboxClaimer claimer;
    private final LibraryReservationIntentMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LibraryReservationEventRelay(
            LibraryReservationOutboxClaimer claimer,
            LibraryReservationIntentMetrics metrics,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.claimer = claimer;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{@libraryReservationIntentProperties.relayInterval.toMillis()}")
    public int relay() {
        List<LibraryReservationOutbox> claimed = claimer.claimBatch();
        claimed.forEach(this::publish);
        claimer.markPublished(claimed);
        return claimed.size();
    }

    private void publish(LibraryReservationOutbox outbox) {
        eventPublisher.publishEvent(new LibraryReservationOutboxEvent(
                outbox.getId(),
                outbox.getEventType(),
                outbox.getIntentId(),
                outbox.getPayload(),
                outbox.getCreatedAt()));
        outbox.markPublished(clock.instant());
        metrics.countRelay(outbox.getEventType());
    }
}
