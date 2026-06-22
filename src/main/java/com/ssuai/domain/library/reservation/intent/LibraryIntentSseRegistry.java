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
    // Conservative per-intentId cap. A single owner only ever needs one live stream per intent;
    // a small slack absorbs legitimate reconnects (a stale emitter the client dropped can linger
    // until its 55s timeout fires). Beyond the cap we evict the OLDEST emitter so a reconnecting
    // client is never the one rejected — combined with empty-key removal this bounds memory even
    // under unbounded distinct intentIds (memory-DoS guard, Codex #26).
    static final int MAX_EMITTERS_PER_INTENT = 4;

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
        // Add inside compute() so the list creation + add are atomic with respect to a concurrent
        // removal that could otherwise drop the key between computeIfAbsent and add (orphaning the
        // new emitter so it never receives the terminal event). The cap is enforced here too.
        emittersByIntentId.compute(intentId, (key, existing) -> {
            List<SseEmitter> emitters = existing == null ? new CopyOnWriteArrayList<>() : existing;
            while (emitters.size() >= MAX_EMITTERS_PER_INTENT) {
                SseEmitter oldest = emitters.remove(0);
                try {
                    oldest.complete();
                } catch (Exception ignored) {
                    // best-effort eviction; the dropped emitter is already being discarded
                }
            }
            emitters.add(emitter);
            return emitters;
        });
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
        // Snapshot the keys: removeDead may drop a key via compute() mid-iteration, and we must
        // never leave an emptied list attached (memory-DoS guard, Codex #26).
        for (Long intentId : emittersByIntentId.keySet()) {
            List<SseEmitter> emitters = emittersByIntentId.get(intentId);
            if (emitters == null) {
                continue;
            }
            List<SseEmitter> dead = new CopyOnWriteArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment(HEARTBEAT_COMMENT));
                } catch (IOException | IllegalStateException ex) {
                    dead.add(emitter);
                }
            }
            removeDead(intentId, dead);
        }
    }

    void removeEmitter(Long intentId, SseEmitter emitter) {
        // compute() so the key is dropped atomically when its list becomes empty. Leaving an empty
        // CopyOnWriteArrayList under the key would let unbounded distinct intentIds accumulate
        // empty entries forever (memory-DoS, Codex #26).
        emittersByIntentId.compute(intentId, (key, emitters) -> {
            if (emitters == null) {
                return null;
            }
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private void removeDead(Long intentId, List<SseEmitter> dead) {
        if (dead.isEmpty()) {
            return;
        }
        emittersByIntentId.compute(intentId, (key, emitters) -> {
            if (emitters == null) {
                return null;
            }
            emitters.removeAll(dead);
            return emitters.isEmpty() ? null : emitters;
        });
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
        if (terminal) {
            // Terminal: drop the whole key regardless of which emitters errored.
            emittersByIntentId.remove(message.intentId());
        } else {
            // Non-terminal: prune only the dead emitters, dropping the key if that empties it.
            removeDead(message.intentId(), dead);
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
