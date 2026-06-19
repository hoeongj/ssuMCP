package com.ssuai.domain.library.reservation.intent;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LibraryReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationEventListener.class);

    private final LibraryIntentStatusBus intentStatusBus;
    private final Clock clock;

    public LibraryReservationEventListener(LibraryIntentStatusBus intentStatusBus, Clock clock) {
        this.intentStatusBus = intentStatusBus;
        this.clock = clock;
    }

    @EventListener
    public void onReservationEvent(LibraryReservationOutboxEvent event) {
        log.info("library reservation intent event: type={} intentId={} outboxId={}",
                event.eventType(), event.intentId(), event.outboxId());
        try {
            intentStatusBus.publish(new LibraryIntentStatusMessage(
                    event.intentId(),
                    event.eventType(),
                    clock.instant()));
        } catch (RuntimeException exception) {
            log.warn("intent status bus publish failed: intentId={}", event.intentId(), exception);
        }
    }
}
