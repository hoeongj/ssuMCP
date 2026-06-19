package com.ssuai.domain.library.reservation.intent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class LibraryIntentSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(LibraryIntentSseRegistry.class);
    private static final long TIMEOUT_MS = 55_000L;
    private static final String HEARTBEAT_COMMENT = "heartbeat";

    private final LibraryIntentStatusBus intentStatusBus;
    private final Map<Long, List<SseEmitter>> emittersByIntentId = new ConcurrentHashMap<>();
    private LibraryIntentStatusBus.Subscription subscription;

    public LibraryIntentSseRegistry(LibraryIntentStatusBus intentStatusBus) {
        this.intentStatusBus = intentStatusBus;
    }

    @PostConstruct
    public void init() {
        this.subscription = intentStatusBus.subscribe(this::onIntentStatusMessage);
        log.info("LibraryIntentSseRegistry initialized");
    }

    @PreDestroy
    public void destroy() {
        if (subscription != null) {
            subscription.close();
        }
        emittersByIntentId.values().forEach(list -> list.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }));
        emittersByIntentId.clear();
    }

    public SseEmitter createEmitter(Long intentId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> emitters = emittersByIntentId.computeIfAbsent(intentId, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(intentId, emitter));
        emitter.onTimeout(() -> removeEmitter(intentId, emitter));
        emitter.onError(ex -> removeEmitter(intentId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"));
        } catch (IOException exception) {
            removeEmitter(intentId, emitter);
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 20_000)
    public void sendHeartbeats() {
        emittersByIntentId.forEach((intentId, emitters) -> {
            List<SseEmitter> dead = new CopyOnWriteArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                } catch (IOException | IllegalStateException ex) {
                    dead.add(emitter);
                }
            }
            if (!dead.isEmpty()) {
                emitters.removeAll(dead);
            }
        });
    }

    private void removeEmitter(Long intentId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByIntentId.get(intentId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    private void onIntentStatusMessage(LibraryIntentStatusMessage message) {
        List<SseEmitter> emitters = emittersByIntentId.get(message.intentId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        boolean terminal = isTerminal(message.eventType());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("intent-status").data(message));
                if (terminal) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException ex) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            emitters.removeAll(dead);
        }
        if (terminal) {
            emittersByIntentId.remove(message.intentId());
        }
    }

    private static boolean isTerminal(LibraryReservationIntentEventType eventType) {
        return switch (eventType) {
            case RESERVATION_SUCCEEDED, RESERVATION_FAILED, CANCELLED, EXPIRED -> true;
            case WAIT_REGISTERED, SEAT_FOUND -> false;
        };
    }

    Map<Long, List<SseEmitter>> getEmittersByIntentId() {
        return emittersByIntentId;
    }
}
