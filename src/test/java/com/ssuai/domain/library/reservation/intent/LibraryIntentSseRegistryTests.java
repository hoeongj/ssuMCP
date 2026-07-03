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

    @Test
    void completionRemovesMapKeyEntirelyLeavingNoEmptyList() {
        SseEmitter emitter = registry.createEmitter(55L);
        assertThat(registry.getEmittersByIntentId()).containsKey(55L);

        // removeEmitter is the callback (onCompletion/onTimeout/onError) target. It must drop the
        // key (not leave an empty CopyOnWriteArrayList behind) so distinct intentIds can't
        // accumulate empty entries forever (memory-DoS guard). Invoked directly here
        // because SseEmitter callbacks are only wired during real MVC async dispatch.
        registry.removeEmitter(55L, emitter);

        assertThat(registry.getEmittersByIntentId()).doesNotContainKey(55L);
    }

    @Test
    void perIntentCapEvictsOldestEmitter() {
        SseEmitter oldest = registry.createEmitter(66L);
        for (int i = 0; i < LibraryIntentSseRegistry.MAX_EMITTERS_PER_INTENT - 1; i++) {
            registry.createEmitter(66L);
        }
        assertThat(registry.getEmittersByIntentId().get(66L))
                .hasSize(LibraryIntentSseRegistry.MAX_EMITTERS_PER_INTENT)
                .contains(oldest);

        // One past the cap evicts the oldest and never exceeds the bound.
        SseEmitter newest = registry.createEmitter(66L);

        assertThat(registry.getEmittersByIntentId().get(66L))
                .hasSize(LibraryIntentSseRegistry.MAX_EMITTERS_PER_INTENT)
                .doesNotContain(oldest)
                .contains(newest);
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
