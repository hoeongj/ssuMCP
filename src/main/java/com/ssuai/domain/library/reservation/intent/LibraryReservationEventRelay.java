package com.ssuai.domain.library.reservation.intent;

import java.time.Clock;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LibraryReservationEventRelay {

    private final LibraryReservationOutboxRepository outboxRepository;
    private final LibraryReservationIntentProperties properties;
    private final LibraryReservationIntentMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LibraryReservationEventRelay(
            LibraryReservationOutboxRepository outboxRepository,
            LibraryReservationIntentProperties properties,
            LibraryReservationIntentMetrics metrics,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{@libraryReservationIntentProperties.relayInterval.toMillis()}")
    @Transactional
    public int relay() {
        List<LibraryReservationOutbox> unpublished =
                outboxRepository.findUnpublished(properties.getRelayBatchSize());
        unpublished.forEach(this::publish);
        return unpublished.size();
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
