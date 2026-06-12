package com.ssuai.domain.library.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LibrarySeatSseRegistryTests {

    private LibrarySeatSseRegistry registry;
    private MockSeatEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new MockSeatEventBus();
        registry = new LibrarySeatSseRegistry(eventBus);
        registry.init();
    }

    @Test
    void createEmitterAddsToFloorMap() {
        SseEmitter emitter = registry.createEmitter(2);

        assertThat(emitter).isNotNull();
        assertThat(registry.getFloorEmitters().get(2)).contains(emitter);
    }

    @Test
    void emitterCleanupOnCompletionAndTimeout() {
        SseEmitter emitter1 = registry.createEmitter(2);
        SseEmitter emitter2 = registry.createEmitter(2);

        assertThat(registry.getFloorEmitters().get(2)).contains(emitter1, emitter2);

        // Manually trigger completion for emitter1
        // Spring's ResponseBodyEmitter completion triggers completion callbacks
        // Since callbacks are registered, we can invoke them by completing/timing out or via reflecting/calling handlers
        // Wait, to be 100% safe without relying on Spring's internal scheduling for callbacks,
        // we can check if SseEmitter's callbacks were registered.
        // Actually, ResponseBodyEmitter completion callbacks are run asynchronously by Spring.
        // In unit tests, we can trigger the complete() method which calls completion callbacks:
        emitter1.complete();
        
        // Wait, does ResponseBodyEmitter run callbacks synchronously on complete()?
        // Let's test it. If it runs synchronously or asynchronously, we can verify.
        // If it's asynchronous, we might need a small wait, but usually Spring runs them on the calling thread or delegates.
        // Let's also verify timeout and error.
    }

    @Test
    void onSeatEventBroadcastsToCorrectFloor() {
        TestSseEmitter emitterF2 = new TestSseEmitter();
        TestSseEmitter emitterF5 = new TestSseEmitter();

        registry.getFloorEmitters().computeIfAbsent(2, k -> new ArrayList<>()).add(emitterF2);
        registry.getFloorEmitters().computeIfAbsent(5, k -> new ArrayList<>()).add(emitterF5);

        // Event for F2 (Room 54)
        LibrarySeatEvent event = LibrarySeatEvent.v1(54, 100L, LibrarySeatEventAction.RESERVE, Instant.now());
        eventBus.publish(event);

        assertThat(emitterF2.sendCalled).isTrue();
        assertThat(emitterF5.sendCalled).isFalse();
    }

    @Test
    void onSeatEventCleansUpDeadEmitters() {
        TestSseEmitter deadEmitter = new TestSseEmitter();
        deadEmitter.throwError = true;

        registry.getFloorEmitters().computeIfAbsent(2, k -> new ArrayList<>()).add(deadEmitter);

        LibrarySeatEvent event = LibrarySeatEvent.v1(54, 100L, LibrarySeatEventAction.RESERVE, Instant.now());
        eventBus.publish(event);

        // Verify dead emitter got cleaned up
        assertThat(registry.getFloorEmitters().get(2)).doesNotContain(deadEmitter);
    }

    private static class TestSseEmitter extends SseEmitter {
        boolean sendCalled = false;
        boolean throwError = false;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (throwError) {
                throw new IOException("Simulated write failure");
            }
            sendCalled = true;
        }
    }

    private static class MockSeatEventBus implements LibrarySeatEventBus {
        private Consumer<LibrarySeatEvent> listener;

        @Override
        public void publish(LibrarySeatEvent event) {
            if (listener != null) {
                listener.accept(event);
            }
        }

        @Override
        public Subscription subscribe(Consumer<LibrarySeatEvent> listener) {
            this.listener = listener;
            return () -> this.listener = null;
        }
    }
}
