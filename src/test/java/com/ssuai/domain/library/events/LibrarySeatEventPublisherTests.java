package com.ssuai.domain.library.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.redis.LibraryRedisMetrics;

class LibrarySeatEventPublisherTests {

    private static final Instant NOW = Instant.parse("2026-06-12T12:00:00Z");

    @Test
    void reserveEventUsesResolvedRoomAndStablePayloadShape() {
        RecordingBus bus = new RecordingBus();
        LibrarySeatEventSeatResolver resolver = mock(LibrarySeatEventSeatResolver.class);
        when(resolver.roomIdForExternalSeatId(3179L)).thenReturn(Optional.of(58));
        LibrarySeatEventPublisher publisher = new LibrarySeatEventPublisher(
                bus,
                resolver,
                new LibraryRedisMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.reserve(null, 3179L);

        assertThat(bus.lastEvent).isEqualTo(new LibrarySeatEvent(
                1,
                58,
                3179L,
                LibrarySeatEventAction.RESERVE,
                NOW));
    }

    @Test
    void publishFailureDoesNotEscapeAndRecordsMetric() {
        LibrarySeatEventSeatResolver resolver = mock(LibrarySeatEventSeatResolver.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LibrarySeatEventPublisher publisher = new LibrarySeatEventPublisher(
                new FailingBus(),
                resolver,
                new LibraryRedisMetrics(meterRegistry),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(() -> publisher.cancel(54, 926L)).doesNotThrowAnyException();

        assertThat(meterRegistry.find("library.redis.failure")
                .tag("operation", "seat_event_publish")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("library.seat_event.publish")
                .tag("outcome", "failure")
                .counter()
                .count()).isEqualTo(1.0);
    }

    private static final class RecordingBus implements LibrarySeatEventBus {
        private LibrarySeatEvent lastEvent;

        @Override
        public void publish(LibrarySeatEvent event) {
            lastEvent = event;
        }

        @Override
        public Subscription subscribe(Consumer<LibrarySeatEvent> listener) {
            return () -> {
            };
        }
    }

    private static final class FailingBus implements LibrarySeatEventBus {
        @Override
        public void publish(LibrarySeatEvent event) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public Subscription subscribe(Consumer<LibrarySeatEvent> listener) {
            return () -> {
            };
        }
    }
}
