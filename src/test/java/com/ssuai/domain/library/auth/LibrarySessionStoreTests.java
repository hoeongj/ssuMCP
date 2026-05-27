package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LibrarySessionStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");

    @Test
    void putThenTokenReturnsValue() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));
        store.put("session-a", "ssotoken-aaaaaa");

        assertThat(store.token("session-a")).hasValue("ssotoken-aaaaaa");
        assertThat(store.has("session-a")).isTrue();
    }

    @Test
    void missingSessionReturnsEmpty() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));

        assertThat(store.token("nope")).isEmpty();
        assertThat(store.has("nope")).isFalse();
    }

    @Test
    void blankSessionKeyReturnsEmpty() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));
        store.put("session-a", "ssotoken-aaaaaa");

        assertThat(store.token("")).isEmpty();
        assertThat(store.token(null)).isEmpty();
    }

    @Test
    void putRejectsBlankInputs() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));

        assertThatThrownBy(() -> store.put("", "tok")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("session", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(null, "tok")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("session", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredEntryIsDroppedOnRead() {
        MutableClock clock = new MutableClock(T0);
        LibrarySessionStore store = new LibrarySessionStore(properties(Duration.ofMinutes(30)), clock);
        store.put("session-a", "ssotoken-aaaaaa");

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.token("session-a")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void invalidateRemovesEntry() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));
        store.put("session-a", "ssotoken-aaaaaa");

        store.invalidate("session-a");

        assertThat(store.token("session-a")).isEmpty();
    }

    @Test
    void lruEvictionWhenOverCapacity() {
        LibrarySessionProperties props = new LibrarySessionProperties();
        props.setTtl(Duration.ofHours(2));
        props.setMaxSessions(3);
        LibrarySessionStore store = new LibrarySessionStore(props, Clock.fixed(T0, ZoneOffset.UTC));

        store.put("a", "tok-a");
        store.put("b", "tok-b");
        store.put("c", "tok-c");
        // Access "a" to mark it recently used, then add "d" — "b" should evict.
        store.token("a");
        store.put("d", "tok-d");

        assertThat(store.has("a")).isTrue();
        assertThat(store.has("b")).isFalse();
        assertThat(store.has("c")).isTrue();
        assertThat(store.has("d")).isTrue();
    }

    @Test
    void fingerprintIsStableAndShort() {
        String fp1 = LibrarySessionStore.fingerprint("ssotoken-aaaaaa");
        String fp2 = LibrarySessionStore.fingerprint("ssotoken-aaaaaa");
        String fp3 = LibrarySessionStore.fingerprint("ssotoken-bbbbbb");

        assertThat(fp1).hasSize(8);
        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).isNotEqualTo(fp3);
    }

    @Test
    void fingerprintForBlankIsNone() {
        assertThat(LibrarySessionStore.fingerprint(null)).isEqualTo("none");
        assertThat(LibrarySessionStore.fingerprint("")).isEqualTo("none");
    }

    private static LibrarySessionStore newStore(Instant now, Duration ttl) {
        return new LibrarySessionStore(properties(ttl), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static LibrarySessionProperties properties(Duration ttl) {
        LibrarySessionProperties props = new LibrarySessionProperties();
        props.setTtl(ttl);
        return props;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration delta) {
            instant = instant.plus(delta);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
