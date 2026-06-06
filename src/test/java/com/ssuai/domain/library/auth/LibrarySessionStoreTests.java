package com.ssuai.domain.library.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = LibrarySessionStoreTests.JpaTestApplication.class)
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LibrarySessionStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");

    @Autowired
    private LibrarySessionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void putThenTokenReturnsValue() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));
        store.put("session-a", "ssotoken-aaaaaa");

        assertThat(store.token("session-a")).hasValue("ssotoken-aaaaaa");
        assertThat(store.has("session-a")).isTrue();
    }

    @Test
    void tokenIsEncryptedAtRest() {
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey("0123456789abcdef0123456789abcde!");
        LibrarySessionStore store = new LibrarySessionStore(repository, props, Clock.fixed(T0, ZoneOffset.UTC));
        store.put("session-a", "ssotoken-aaaaaa");

        LibrarySessionEntity entity = repository.findById("session-a").orElseThrow();
        byte[] cipherBytes = Base64.getDecoder().decode(entity.getCipherB64());

        assertThat(entity.getIvB64()).isNotBlank();
        assertThat(Base64.getDecoder().decode(entity.getIvB64())).hasSize(12);
        assertThat(containsSubsequence(cipherBytes,
                "ssotoken-aaaaaa".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(store.token("session-a")).hasValue("ssotoken-aaaaaa");
    }

    @Test
    void configuredBase64EncryptionKeyRoundTrips() {
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey(Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        LibrarySessionStore store = new LibrarySessionStore(repository, props, Clock.fixed(T0, ZoneOffset.UTC));

        store.put("session-a", "ssotoken-aaaaaa");

        assertThat(store.token("session-a")).hasValue("ssotoken-aaaaaa");
    }

    @Test
    void shortEncryptionKeyFailsFast() {
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey("short");

        assertThatThrownBy(
                () -> new LibrarySessionStore(repository, props, Clock.fixed(T0, ZoneOffset.UTC)))
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
        LibrarySessionStore store = new LibrarySessionStore(repository, properties(Duration.ofMinutes(30)), clock);
        store.put("session-a", "ssotoken-aaaaaa");

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.token("session-a")).isEmpty();
        assertThat(repository.findById("session-a")).isEmpty();
    }

    @Test
    void invalidateRemovesEntry() {
        LibrarySessionStore store = newStore(T0, Duration.ofHours(2));
        store.put("session-a", "ssotoken-aaaaaa");

        store.invalidate("session-a");

        assertThat(store.token("session-a")).isEmpty();
        assertThat(repository.findById("session-a")).isEmpty();
    }

    @Test
    void cleanupExpiredSessionsDeletesExpiredRows() {
        MutableClock clock = new MutableClock(T0);
        LibrarySessionStore store = new LibrarySessionStore(repository, properties(Duration.ofMinutes(30)), clock);
        store.put("session-a", "ssotoken-aaaaaa");
        assertThat(store.size()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(31));
        store.cleanupExpiredSessions();

        assertThat(store.size()).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    void sessionSurvivesStoreRecreation() {
        String fixedKey = "0123456789abcdef0123456789abcde!";
        LibrarySessionProperties props = properties(Duration.ofHours(2));
        props.setEncryptionKey(fixedKey);

        new LibrarySessionStore(repository, props, Clock.fixed(T0, ZoneOffset.UTC))
                .put("session-a", "ssotoken-aaaaaa");

        LibrarySessionStore secondStore =
                new LibrarySessionStore(repository, props, Clock.fixed(T0.plusSeconds(1), ZoneOffset.UTC));

        assertThat(secondStore.token("session-a")).hasValue("ssotoken-aaaaaa");
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

    private LibrarySessionStore newStore(Instant now, Duration ttl) {
        return new LibrarySessionStore(repository, properties(ttl), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static LibrarySessionProperties properties(Duration ttl) {
        LibrarySessionProperties props = new LibrarySessionProperties();
        props.setTtl(ttl);
        return props;
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = LibrarySessionEntity.class)
    @EnableJpaRepositories(basePackageClasses = LibrarySessionRepository.class)
    static class JpaTestApplication {
    }
}
