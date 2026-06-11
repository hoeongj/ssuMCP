package com.ssuai.domain.library.reservation.intent;

import java.util.Set;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class LibraryReservationIntentMetrics {

    static final Set<LibraryReservationIntentStatus> ACTIVE_STATUSES = Set.of(
            LibraryReservationIntentStatus.REQUESTED,
            LibraryReservationIntentStatus.WAITING_FOR_SEAT,
            LibraryReservationIntentStatus.RESERVING);

    private final MeterRegistry meterRegistry;

    public LibraryReservationIntentMetrics(
            MeterRegistry meterRegistry,
            LibraryReservationIntentRepository intentRepository,
            LibraryReservationOutboxRepository outboxRepository) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("library.intent.depth", intentRepository,
                        repository -> repository.countByStatusIn(ACTIVE_STATUSES))
                .register(meterRegistry);
        Gauge.builder("library.intent.outbox.unpublished", outboxRepository,
                        LibraryReservationOutboxRepository::countByPublishedAtIsNull)
                .register(meterRegistry);
    }

    public void countTransition(LibraryReservationIntentStatus status, String outcomeCode) {
        meterRegistry.counter("library.intent",
                        "status", status.name(),
                        "outcome", outcomeCode == null ? "NONE" : outcomeCode)
                .increment();
    }

    public void countRelay(LibraryReservationIntentEventType eventType) {
        meterRegistry.counter("library.intent.outbox.relay", "event_type", eventType.name()).increment();
    }
}
