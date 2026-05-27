package com.ssuai.domain.library.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory store of captured library session tokens, keyed by ssuAI session id
 * (Spring HttpSession id for MVP). Values are the upstream `Pyxis-Auth-Token`
 * captured from oasis.ssu.ac.kr after the user logs in on its own page.
 *
 * The raw token never leaves this class outside of the connector that needs it
 * to drive an upstream `Pyxis-Auth-Token` request header. All log messages use
 * the 8-char fingerprint via {@link #fingerprint(String)}.
 */
@Component
public class LibrarySessionStore {

    private final LibrarySessionProperties properties;
    private final Clock clock;
    private final Map<String, Entry> entries;

    @Autowired
    public LibrarySessionStore(LibrarySessionProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public LibrarySessionStore(LibrarySessionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        int cap = Math.max(1, properties.getMaxSessions());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > cap;
            }
        };
    }

    public void put(String sessionKey, String token) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        Instant now = clock.instant();
        Entry entry = new Entry(token, now, now.plus(properties.getTtl()));
        synchronized (entries) {
            entries.put(sessionKey, entry);
        }
    }

    public Optional<String> token(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        synchronized (entries) {
            Entry entry = entries.get(sessionKey);
            if (entry == null) {
                return Optional.empty();
            }
            if (entry.expiresAt.isBefore(clock.instant())) {
                entries.remove(sessionKey);
                return Optional.empty();
            }
            return Optional.of(entry.token);
        }
    }

    public boolean has(String sessionKey) {
        return token(sessionKey).isPresent();
    }

    public void invalidate(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        synchronized (entries) {
            entries.remove(sessionKey);
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public static String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Entry(String token, Instant capturedAt, Instant expiresAt) {

        Entry {
            if (token == null) {
                throw new IllegalArgumentException("token required");
            }
            if (capturedAt == null || expiresAt == null) {
                throw new IllegalArgumentException("timestamps required");
            }
        }
    }
}
