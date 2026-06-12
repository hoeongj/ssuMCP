package com.ssuai.domain.library.events;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.domain.library.redis.LibraryRedisMetrics;

@Service
public class LibrarySeatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatEventPublisher.class);

    private final LibrarySeatEventBus eventBus;
    private final LibrarySeatEventSeatResolver seatResolver;
    private final LibraryRedisMetrics metrics;
    private final Clock clock;

    public LibrarySeatEventPublisher(
            LibrarySeatEventBus eventBus,
            LibrarySeatEventSeatResolver seatResolver,
            LibraryRedisMetrics metrics,
            Clock clock) {
        this.eventBus = eventBus;
        this.seatResolver = seatResolver;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void reserve(Integer roomId, Long seatId) {
        publish(LibrarySeatEventAction.RESERVE, roomId, seatId);
    }

    public void cancel(Integer roomId, Long seatId) {
        publish(LibrarySeatEventAction.CANCEL, roomId, seatId);
    }

    public void swapDischarge(Integer roomId, Long seatId) {
        publish(LibrarySeatEventAction.SWAP_DISCHARGE, roomId, seatId);
    }

    public void swapReserve(Integer roomId, Long seatId) {
        publish(LibrarySeatEventAction.SWAP_RESERVE, roomId, seatId);
    }

    private void publish(LibrarySeatEventAction action, Integer roomId, Long seatId) {
        Integer effectiveRoomId = roomId != null
                ? roomId
                : seatResolver.roomIdForExternalSeatId(seatId).orElse(null);
        LibrarySeatEvent event = LibrarySeatEvent.v1(effectiveRoomId, seatId, action, clock.instant());
        try {
            eventBus.publish(event);
            metrics.countSeatEventPublish("success");
        } catch (RuntimeException exception) {
            log.warn("library seat event publish failed: action={} roomId={} seatId={}",
                    action, effectiveRoomId, seatId, exception);
            metrics.countFailure("seat_event_publish", exception);
            metrics.countSeatEventPublish("failure");
        }
    }
}
