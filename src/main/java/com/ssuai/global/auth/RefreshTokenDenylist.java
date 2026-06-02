package com.ssuai.global.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class RefreshTokenDenylist {

    private final Clock clock;
    private final Map<String, Instant> deniedJtis = new ConcurrentHashMap<>();

    public RefreshTokenDenylist() {
        this(Clock.systemUTC());
    }

    RefreshTokenDenylist(Clock clock) {
        this.clock = clock;
    }

    public void deny(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        if (expiresAt.isAfter(clock.instant())) {
            deniedJtis.put(jti, expiresAt);
        }
        pruneExpired();
    }

    public boolean isDenied(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Instant expiresAt = deniedJtis.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            deniedJtis.remove(jti, expiresAt);
            return false;
        }
        return true;
    }

    int size() {
        pruneExpired();
        return deniedJtis.size();
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        deniedJtis.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
