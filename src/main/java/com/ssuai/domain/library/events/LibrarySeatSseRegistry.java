package com.ssuai.domain.library.events;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class LibrarySeatSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(LibrarySeatSseRegistry.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final LibrarySeatEventBus eventBus;
    private final Map<Integer, List<SseEmitter>> floorEmitters = new ConcurrentHashMap<>();
    private LibrarySeatEventBus.Subscription subscription;

    public LibrarySeatSseRegistry(LibrarySeatEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        this.subscription = eventBus.subscribe(this::onSeatEvent);
        log.info("LibrarySeatSseRegistry initialized and subscribed to seat events");
    }

    @PreDestroy
    public void destroy() {
        if (subscription != null) {
            subscription.close();
        }
        floorEmitters.values().forEach(list -> list.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (Exception ex) {
                // Ignore failure during cleanup
            }
        }));
        floorEmitters.clear();
        log.info("LibrarySeatSseRegistry destroyed");
    }

    public SseEmitter createEmitter(int floorCode) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> emitters = floorEmitters.computeIfAbsent(floorCode, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(floorCode, emitter));
        emitter.onTimeout(() -> removeEmitter(floorCode, emitter));
        emitter.onError(ex -> removeEmitter(floorCode, emitter));

        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"));
        } catch (IOException e) {
            removeEmitter(floorCode, emitter);
        }

        return emitter;
    }

    private void removeEmitter(int floorCode, SseEmitter emitter) {
        List<SseEmitter> emitters = floorEmitters.get(floorCode);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    private void onSeatEvent(LibrarySeatEvent event) {
        if (event.roomId() == null) {
            return;
        }

        int floorCode = getFloorCodeFromRoomId(event.roomId());
        if (floorCode == 0) {
            return;
        }

        List<SseEmitter> emitters = floorEmitters.get(floorCode);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("seat-update")
                        .data(event));
            } catch (IOException | IllegalStateException ex) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }

    private int getFloorCodeFromRoomId(int roomId) {
        if (roomId == 53 || roomId == 54) {
            return 2;
        }
        if (roomId == 59 || roomId == 60) {
            return 5;
        }
        if (roomId == 57 || roomId == 58) {
            return 6;
        }
        return 0;
    }

    // Package-private method for testing
    Map<Integer, List<SseEmitter>> getFloorEmitters() {
        return floorEmitters;
    }
}
