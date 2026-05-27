package com.ssuai.domain.chat.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.domain.chat.config.ChatMemoryProperties;

@Component
public class ChatConversationStore {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public record Turn(String role, String content) {
    }

    private final ChatMemoryProperties properties;
    private final Clock clock;
    private final Map<String, Entry> entries;

    @Autowired
    public ChatConversationStore(ChatMemoryProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ChatConversationStore(ChatMemoryProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        int cap = Math.max(1, properties.getMaxConversations());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > cap;
            }
        };
    }

    public List<Turn> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        synchronized (entries) {
            Entry entry = entries.get(conversationId);
            if (entry == null) {
                return List.of();
            }
            if (isExpired(entry)) {
                entries.remove(conversationId);
                return List.of();
            }
            entry.touch(clock.instant());
            return List.copyOf(entry.turns());
        }
    }

    public void appendUser(String conversationId, String message) {
        append(conversationId, new Turn(ROLE_USER, message));
    }

    public void appendAssistant(String conversationId, String reply) {
        append(conversationId, new Turn(ROLE_ASSISTANT, reply));
    }

    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        synchronized (entries) {
            entries.remove(conversationId);
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private void append(String conversationId, Turn turn) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (turn.content() == null || turn.content().isBlank()) {
            return;
        }
        synchronized (entries) {
            evictExpired();
            Entry entry = entries.get(conversationId);
            if (entry == null) {
                entry = new Entry(new ArrayDeque<>(), clock.instant());
                entries.put(conversationId, entry);
            }
            entry.turns().addLast(turn);
            int cap = Math.max(2, properties.getMaxMessages());
            while (entry.turns().size() > cap) {
                entry.turns().removeFirst();
            }
            entry.touch(clock.instant());
        }
    }

    private void evictExpired() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        List<String> toRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry> mapEntry = iterator.next();
            if (isExpired(mapEntry.getValue())) {
                toRemove.add(mapEntry.getKey());
            }
        }
        for (String key : toRemove) {
            entries.remove(key);
        }
    }

    private boolean isExpired(Entry entry) {
        Duration ttl = properties.getTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        Instant now = clock.instant();
        return Duration.between(entry.lastAccessAt(), now).compareTo(ttl) > 0;
    }

    private static final class Entry {

        private final Deque<Turn> turns;
        private Instant lastAccessAt;

        private Entry(Deque<Turn> turns, Instant lastAccessAt) {
            this.turns = turns;
            this.lastAccessAt = lastAccessAt;
        }

        private Deque<Turn> turns() {
            return turns;
        }

        private Instant lastAccessAt() {
            return lastAccessAt;
        }

        private void touch(Instant now) {
            this.lastAccessAt = now;
        }
    }
}
