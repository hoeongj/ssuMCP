package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LibraryIntentSseRegistryTests {

    private FakeIntentStatusBus bus;
    private LibraryIntentSseRegistry registry;

    @BeforeEach
    void setUp() {
        bus = new FakeIntentStatusBus();
        registry = new LibraryIntentSseRegistry(bus);
        registry.init();
    }

    @Test
    void createEmitterRegistersForIntentId() {
        SseEmitter emitter = registry.createEmitter(42L);
        assertThat(emitter).isNotNull();
        assertThat(registry.getEmittersByIntentId()).containsKey(42L);
    }

    @Test
    void terminalEventCompletesAndRemovesEmitter() {
        registry.createEmitter(99L);
        assertThat(registry.getEmittersByIntentId()).containsKey(99L);

        bus.deliver(new LibraryIntentStatusMessage(
                99L, LibraryReservationIntentEventType.RESERVATION_SUCCEEDED, Instant.now()));

        assertThat(registry.getEmittersByIntentId()).doesNotContainKey(99L);
    }

    @Test
    void nonTerminalEventKeepsEmitterAlive() {
        registry.createEmitter(77L);

        bus.deliver(new LibraryIntentStatusMessage(
                77L, LibraryReservationIntentEventType.SEAT_FOUND, Instant.now()));

        assertThat(registry.getEmittersByIntentId()).containsKey(77L);
    }

    @Test
    void eventForDifferentIntentIdHasNoEffect() {
        registry.createEmitter(11L);

        bus.deliver(new LibraryIntentStatusMessage(
                22L, LibraryReservationIntentEventType.RESERVATION_SUCCEEDED, Instant.now()));

        assertThat(registry.getEmittersByIntentId()).containsKey(11L);
    }

    private static final class FakeIntentStatusBus implements LibraryIntentStatusBus {
        private Consumer<LibraryIntentStatusMessage> listener;

        @Override
        public void publish(LibraryIntentStatusMessage message) {
        }

        @Override
        public Subscription subscribe(Consumer<LibraryIntentStatusMessage> listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }

        void deliver(LibraryIntentStatusMessage message) {
            if (listener != null) {
                listener.accept(message);
            }
        }
    }
}
