package com.ssuai.domain.library.reservation.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class LibraryReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationEventListener.class);

    @EventListener
    public void onReservationEvent(LibraryReservationOutboxEvent event) {
        log.info("library reservation intent event: type={} intentId={} outboxId={}",
                event.eventType(), event.intentId(), event.outboxId());
    }
}
