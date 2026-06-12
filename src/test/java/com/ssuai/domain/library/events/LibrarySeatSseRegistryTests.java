package com.ssuai.domain.library.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

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
        assertThat(emitter.getTimeout()).isEqualTo(55_000L);
        assertThat(registry.getFloorEmitters().get(2)).contains(emitter);
    }

    @Test
    void destroyCleansUpEmittersAndSubscription() {
        SseEmitter emitter1 = registry.createEmitter(2);
        SseEmitter emitter2 = registry.createEmitter(2);

        assertThat(registry.getFloorEmitters().get(2)).contains(emitter1, emitter2);

        registry.destroy();

        assertThat(registry.getFloorEmitters()).isEmpty();
        assertThat(eventBus.listener).isNull();
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

    @Test
    void heartbeatSendsCommentToRegisteredEmitters() {
        TestSseEmitter emitter = new TestSseEmitter();
        registry.getFloorEmitters().computeIfAbsent(2, k -> new CopyOnWriteArrayList<>()).add(emitter);

        registry.sendHeartbeats();

        assertThat(emitter.sendCalled).isTrue();
        assertThat(emitter.lastSentEvent).contains(":heartbeat");
        assertThat(registry.getFloorEmitters().get(2)).contains(emitter);
    }

    @Test
    void heartbeatRemovesDeadEmitters() {
        TestSseEmitter deadEmitter = new TestSseEmitter();
        deadEmitter.throwError = true;
        registry.getFloorEmitters().computeIfAbsent(2, k -> new CopyOnWriteArrayList<>()).add(deadEmitter);

        registry.sendHeartbeats();

        assertThat(registry.getFloorEmitters().get(2)).doesNotContain(deadEmitter);
    }

    private static class TestSseEmitter extends SseEmitter {
        boolean sendCalled = false;
        boolean throwError = false;
        String lastSentEvent = "";

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (throwError) {
                throw new IOException("Simulated write failure");
            }
            sendCalled = true;
            lastSentEvent = extractEventText(builder);
        }

        private String extractEventText(SseEventBuilder builder) {
            try {
                Field sbField = builder.getClass().getDeclaredField("sb");
                sbField.setAccessible(true);
                return sbField.get(builder).toString();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Failed to inspect SSE event builder", ex);
            }
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
