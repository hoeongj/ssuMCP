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

    /**
     * Composite map key isolating a conversation to its owner. {@code owner}
     * is the authenticated student id when present, else the server session
     * id (see {@code ChatController}). Keying by {@code (owner,
     * conversationId)} instead of {@code conversationId} alone stops one
     * caller from reading another caller's history by passing (or guessing)
     * their {@code conversationId}.
     */
    private record Key(String owner, String conversationId) {
    }

    private final ChatMemoryProperties properties;
    private final Clock clock;
    private final Map<Key, Entry> entries;

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
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > cap;
            }
        };
    }

    public List<Turn> history(String owner, String conversationId) {
        if (isBlank(owner) || isBlank(conversationId)) {
            return List.of();
        }
        Key key = new Key(owner, conversationId);
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return List.of();
            }
            if (isExpired(entry)) {
                entries.remove(key);
                return List.of();
            }
            entry.touch(clock.instant());
            return List.copyOf(entry.turns());
        }
    }

    public void appendUser(String owner, String conversationId, String message) {
        append(owner, conversationId, new Turn(ROLE_USER, message));
    }

    public void appendAssistant(String owner, String conversationId, String reply) {
        append(owner, conversationId, new Turn(ROLE_ASSISTANT, reply));
    }

    public boolean isPrivate(String owner, String conversationId) {
        if (isBlank(owner) || isBlank(conversationId)) {
            return false;
        }
        Key key = new Key(owner, conversationId);
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null || isExpired(entry)) {
                entries.remove(key);
                return false;
            }
            return entry.privateData();
        }
    }

    public void markPrivate(String owner, String conversationId) {
        if (isBlank(owner) || isBlank(conversationId)) {
            return;
        }
        synchronized (entries) {
            Entry entry = entries.get(new Key(owner, conversationId));
            if (entry != null && !isExpired(entry)) {
                entry.markPrivate();
            }
        }
    }

    public void clear(String owner, String conversationId) {
        if (isBlank(owner) || isBlank(conversationId)) {
            return;
        }
        synchronized (entries) {
            entries.remove(new Key(owner, conversationId));
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    private void append(String owner, String conversationId, Turn turn) {
        if (isBlank(owner) || isBlank(conversationId)) {
            return;
        }
        if (turn.content() == null || turn.content().isBlank()) {
            return;
        }
        Key key = new Key(owner, conversationId);
        synchronized (entries) {
            evictExpired();
            Entry entry = entries.get(key);
            if (entry == null) {
                entry = new Entry(new ArrayDeque<>(), clock.instant());
                entries.put(key, entry);
            }
            entry.turns().addLast(turn);
            int cap = Math.max(2, properties.getMaxMessages());
            while (entry.turns().size() > cap) {
                entry.turns().removeFirst();
            }
            entry.touch(clock.instant());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void evictExpired() {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        List<Key> toRemove = new ArrayList<>();
        while (iterator.hasNext()) {
            Map.Entry<Key, Entry> mapEntry = iterator.next();
            if (isExpired(mapEntry.getValue())) {
                toRemove.add(mapEntry.getKey());
            }
        }
        for (Key key : toRemove) {
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
        private boolean privateData;

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

        private boolean privateData() {
            return privateData;
        }

        private void markPrivate() {
            this.privateData = true;
        }
    }
}
