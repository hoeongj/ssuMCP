package com.ssuai.domain.auth.saint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SaintSessionStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-16T10:00:00Z");
    private static final String COOKIE = "MYSAPSSO2=portal-session-abc; JSESSIONID=jsess-xyz";

    @Test
    void putThenCookiesReturnsPlaintext() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));

        store.put("20231234", new PortalCookies(COOKIE));

        Optional<PortalCookies> read = store.cookies("20231234");
        assertThat(read).isPresent();
        assertThat(read.get().rawCookieHeader()).isEqualTo(COOKIE);
        assertThat(store.has("20231234")).isTrue();
    }

    @Test
    void missingStudentIdReturnsEmpty() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));

        assertThat(store.cookies("nope")).isEmpty();
        assertThat(store.has("nope")).isFalse();
    }

    @Test
    void blankStudentIdLookupReturnsEmpty() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));
        store.put("20231234", new PortalCookies(COOKIE));

        assertThat(store.cookies(null)).isEmpty();
        assertThat(store.cookies("")).isEmpty();
        assertThat(store.cookies("   ")).isEmpty();
    }

    @Test
    void putRejectsBlankInputs() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));

        assertThatThrownBy(() -> store.put(null, new PortalCookies(COOKIE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("", new PortalCookies(COOKIE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("20231234", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalCookies(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PortalCookies(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredEntryIsDroppedOnRead() {
        MutableClock clock = new MutableClock(T0);
        SaintSessionStore store = new SaintSessionStore(
                properties(Duration.ofMinutes(30), ""), clock, new SecureRandom());
        store.put("20231234", new PortalCookies(COOKIE));

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.cookies("20231234")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void invalidateRemovesEntry() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));
        store.put("20231234", new PortalCookies(COOKIE));

        store.invalidate("20231234");

        assertThat(store.cookies("20231234")).isEmpty();
    }

    @Test
    void invalidateBlankStudentIdIsNoOp() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));
        store.put("20231234", new PortalCookies(COOKIE));

        store.invalidate(null);
        store.invalidate("");

        assertThat(store.has("20231234")).isTrue();
    }

    @Test
    void lruEvictionWhenOverCapacity() {
        SaintSessionProperties props = properties(Duration.ofHours(1), "");
        props.setMaxSessions(3);
        SaintSessionStore store = new SaintSessionStore(
                props, Clock.fixed(T0, ZoneOffset.UTC), new SecureRandom());

        store.put("a", new PortalCookies("ca"));
        store.put("b", new PortalCookies("cb"));
        store.put("c", new PortalCookies("cc"));
        // Touch "a" so the LRU eviction order targets "b" next.
        store.cookies("a");
        store.put("d", new PortalCookies("cd"));

        assertThat(store.has("a")).isTrue();
        assertThat(store.has("b")).isFalse();
        assertThat(store.has("c")).isTrue();
        assertThat(store.has("d")).isTrue();
    }

    @Test
    void longCookieHeaderRoundTripsLossless() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));
        String longHeader = "MYSAPSSO2=" + "a".repeat(2048) + "; JSESSIONID=" + "b".repeat(1024);

        store.put("20231234", new PortalCookies(longHeader));

        assertThat(store.cookies("20231234").orElseThrow().rawCookieHeader())
                .isEqualTo(longHeader);
    }

    @Test
    void differentEntriesAreIsolated() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));

        store.put("20231234", new PortalCookies("cookie-A"));
        store.put("20235678", new PortalCookies("cookie-B"));

        assertThat(store.cookies("20231234").orElseThrow().rawCookieHeader()).isEqualTo("cookie-A");
        assertThat(store.cookies("20235678").orElseThrow().rawCookieHeader()).isEqualTo("cookie-B");
    }

    @Test
    void rewritingSameStudentIdReplacesPriorCookies() {
        SaintSessionStore store = ephemeralStore(T0, Duration.ofMinutes(30));

        store.put("20231234", new PortalCookies("old"));
        store.put("20231234", new PortalCookies("new"));

        assertThat(store.cookies("20231234").orElseThrow().rawCookieHeader()).isEqualTo("new");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void emptyEncryptionKeyBootsWithEphemeralKey() {
        SaintSessionStore store = new SaintSessionStore(
                properties(Duration.ofMinutes(30), ""),
                Clock.fixed(T0, ZoneOffset.UTC),
                new SecureRandom());

        store.put("20231234", new PortalCookies(COOKIE));

        assertThat(store.cookies("20231234").orElseThrow().rawCookieHeader()).isEqualTo(COOKIE);
    }

    @Test
    void configuredBase64KeyRoundTrips() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        SaintSessionStore store = new SaintSessionStore(
                properties(Duration.ofMinutes(30), base64Key),
                Clock.fixed(T0, ZoneOffset.UTC),
                new SecureRandom());

        store.put("20231234", new PortalCookies(COOKIE));

        assertThat(store.cookies("20231234").orElseThrow().rawCookieHeader()).isEqualTo(COOKIE);
    }

    @Test
    void shortConfiguredKeyFailsAtBoot() {
        SaintSessionProperties props = properties(Duration.ofMinutes(30), "too-short-for-aes-256");

        assertThatThrownBy(() -> new SaintSessionStore(props, Clock.fixed(T0, ZoneOffset.UTC), new SecureRandom()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void fingerprintIsStableAndShort() {
        String fp1 = SaintSessionStore.fingerprint("20231234");
        String fp2 = SaintSessionStore.fingerprint("20231234");
        String fp3 = SaintSessionStore.fingerprint("20235678");

        assertThat(fp1).hasSize(8);
        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).isNotEqualTo(fp3);
    }

    @Test
    void fingerprintForBlankIsNone() {
        assertThat(SaintSessionStore.fingerprint(null)).isEqualTo("none");
        assertThat(SaintSessionStore.fingerprint("")).isEqualTo("none");
        assertThat(SaintSessionStore.fingerprint("   ")).isEqualTo("none");
    }

    private static SaintSessionStore ephemeralStore(Instant now, Duration ttl) {
        return new SaintSessionStore(
                properties(ttl, ""),
                Clock.fixed(now, ZoneOffset.UTC),
                new SecureRandom());
    }

    private static SaintSessionProperties properties(Duration ttl, String encryptionKey) {
        SaintSessionProperties props = new SaintSessionProperties();
        props.setTtl(ttl);
        props.setEncryptionKey(encryptionKey);
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
