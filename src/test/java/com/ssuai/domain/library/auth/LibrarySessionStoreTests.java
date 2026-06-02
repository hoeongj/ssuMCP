package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

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
    void tokenIsEncryptedAtRest() throws Exception {
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey("0123456789abcdef0123456789abcde!");
        LibrarySessionStore store = new LibrarySessionStore(props, Clock.fixed(T0, ZoneOffset.UTC));
        store.put("session-a", "ssotoken-aaaaaa");

        Object entry = entries(store).get("session-a");
        byte[] ciphertext = recordBytes(entry, "ciphertext");

        assertThat(entry.toString()).doesNotContain("ssotoken-aaaaaa");
        assertThat(recordBytes(entry, "iv")).hasSize(12);
        assertThat(containsSubsequence(ciphertext, "ssotoken-aaaaaa".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(store.token("session-a")).hasValue("ssotoken-aaaaaa");
    }

    @Test
    void configuredBase64EncryptionKeyRoundTrips() {
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey(Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        LibrarySessionStore store = new LibrarySessionStore(props, Clock.fixed(T0, ZoneOffset.UTC));

        store.put("session-a", "ssotoken-aaaaaa");

        assertThat(store.token("session-a")).hasValue("ssotoken-aaaaaa");
    }

    @Test
    void shortEncryptionKeyFailsFast() {
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey("short");

        assertThatThrownBy(() -> new LibrarySessionStore(props, Clock.fixed(T0, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
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

    @SuppressWarnings("unchecked")
    private static Map<String, ?> entries(LibrarySessionStore store) throws Exception {
        Field field = LibrarySessionStore.class.getDeclaredField("entries");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(store);
    }

    private static byte[] recordBytes(Object entry, String accessorName) throws Exception {
        Method accessor = entry.getClass().getDeclaredMethod(accessorName);
        accessor.setAccessible(true);
        return (byte[]) accessor.invoke(entry);
    }

    private static boolean containsSubsequence(byte[] bytes, byte[] candidate) {
        if (candidate.length == 0 || candidate.length > bytes.length) {
            return false;
        }
        for (int start = 0; start <= bytes.length - candidate.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < candidate.length; offset++) {
                if (bytes[start + offset] != candidate[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
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
