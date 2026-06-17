package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpAuthSessionStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    @Autowired
    private McpSessionRepository repository;

    private final ObjectMapper objectMapper = McpAuthSessionStore.defaultObjectMapper();


    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void createReturnsSessionWithId() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));

        McpAuthSession session = store.create();

        assertThat(session.id()).isNotNull();
        assertThat(session.id().value()).isNotBlank();
        assertThat(session.providers()).isEmpty();
        assertThat(session.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
        assertThat(repository.findById(session.id().value())).isPresent();
    }

    @Test
    void findReturnsCreatedSession() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession created = store.create();

        Optional<McpAuthSession> found = store.find(created.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(created.id());
    }

    @Test
    void findByStringValueWorks() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession created = store.create();

        Optional<McpAuthSession> found = store.find(created.id().value());

        assertThat(found).isPresent();
    }

    @Test
    void findUnknownIdReturnsEmpty() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        assertThat(store.find(McpAuthSessionId.generate())).isEmpty();
    }

    @Test
    void findBlankIdReturnsEmpty() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        assertThat(store.find((McpAuthSessionId) null)).isEmpty();
        assertThat(store.find((String) null)).isEmpty();
        assertThat(store.find("")).isEmpty();
        assertThat(store.find("   ")).isEmpty();
    }

    @Test
    void expiredSessionIsDroppedOnRead() {
        MutableClock clock = new MutableClock(T0);
        McpAuthSessionStore store = store(clock, Duration.ofHours(4));
        McpAuthSession session = store.create();

        clock.advance(Duration.ofHours(5));

        assertThat(store.find(session.id())).isEmpty();
        assertThat(store.size()).isZero();
        assertThat(repository.findById(session.id().value())).isEmpty();
    }

    @Test
    void linkProviderAppearsInSession() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.linkProvider(session.id(), McpProviderType.SAINT, "20231234");

        McpAuthSession updated = store.find(session.id()).orElseThrow();
        assertThat(updated.isLinked(McpProviderType.SAINT)).isTrue();
        assertThat(updated.provider(McpProviderType.SAINT).orElseThrow().principalKey())
                .isEqualTo("20231234");
        assertThat(repository.findById(session.id().value()).orElseThrow().getProviders())
                .contains("\"SAINT\"");
    }

    @Test
    void linkProviderDoesNotAffectOtherProviders() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.linkProvider(session.id(), McpProviderType.SAINT, "20231234");
        store.linkProvider(session.id(), McpProviderType.LMS, "20231234");

        McpAuthSession updated = store.find(session.id()).orElseThrow();
        assertThat(updated.isLinked(McpProviderType.SAINT)).isTrue();
        assertThat(updated.isLinked(McpProviderType.LMS)).isTrue();
        assertThat(updated.isLinked(McpProviderType.LIBRARY)).isFalse();
    }

    @Test
    void linkProviderReplacesExistingLink() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.linkProvider(session.id(), McpProviderType.SAINT, "old-key");
        store.linkProvider(session.id(), McpProviderType.SAINT, "new-key");

        McpAuthSession updated = store.find(session.id()).orElseThrow();
        assertThat(updated.provider(McpProviderType.SAINT).orElseThrow().principalKey())
                .isEqualTo("new-key");
    }

    @Test
    void unlinkProviderRemovesLink() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();
        store.linkProvider(session.id(), McpProviderType.SAINT, "20231234");

        store.unlinkProvider(session.id(), McpProviderType.SAINT);

        McpAuthSession updated = store.find(session.id()).orElseThrow();
        assertThat(updated.isLinked(McpProviderType.SAINT)).isFalse();
    }

    @Test
    void unlinkNonexistentProviderIsNoOp() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.unlinkProvider(session.id(), McpProviderType.SAINT);

        assertThat(store.find(session.id())).isPresent();
    }

    @Test
    void linkProviderWithNullArgsIsNoOp() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.linkProvider(null, McpProviderType.SAINT, "key");
        store.linkProvider(session.id(), null, "key");
        store.linkProvider(session.id(), McpProviderType.SAINT, null);
        store.linkProvider(session.id(), McpProviderType.SAINT, "");

        assertThat(store.find(session.id()).orElseThrow().isLinked(McpProviderType.SAINT)).isFalse();
    }

    @Test
    void unlinkProviderWithNullArgsIsNoOp() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();
        store.linkProvider(session.id(), McpProviderType.SAINT, "key");

        store.unlinkProvider(null, McpProviderType.SAINT);
        store.unlinkProvider(session.id(), null);

        assertThat(store.find(session.id()).orElseThrow().isLinked(McpProviderType.SAINT)).isTrue();
    }

    @Test
    void invalidateRemovesSession() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.invalidate(session.id());

        assertThat(store.find(session.id())).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void invalidateNullIsNoOp() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        store.create();

        store.invalidate(null);

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void cleanupExpiredSessionsDeletesExpiredRows() {
        MutableClock clock = new MutableClock(T0);
        McpAuthSessionStore store = store(clock, Duration.ofHours(4));
        store.create();

        clock.advance(Duration.ofHours(5));
        store.cleanupExpiredSessions();

        assertThat(store.size()).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    void providersSurviveStoreRecreation() {
        McpAuthSessionStore firstStore = store(T0, Duration.ofHours(4));
        McpAuthSession session = firstStore.create();
        firstStore.linkProvider(session.id(), McpProviderType.SAINT, "student-A");
        firstStore.linkProvider(session.id(), McpProviderType.LIBRARY, "library-session-key");

        McpAuthSessionStore secondStore = store(T0.plusSeconds(1), Duration.ofHours(4));

        McpAuthSession restored = secondStore.find(session.id()).orElseThrow();
        assertThat(restored.provider(McpProviderType.SAINT).orElseThrow().principalKey())
                .isEqualTo("student-A");
        assertThat(restored.provider(McpProviderType.LIBRARY).orElseThrow().principalKey())
                .isEqualTo("library-session-key");
    }

    @Test
    void multipleSessionsAreIsolated() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession s1 = store.create();
        McpAuthSession s2 = store.create();

        store.linkProvider(s1.id(), McpProviderType.SAINT, "student-A");
        store.linkProvider(s2.id(), McpProviderType.SAINT, "student-B");

        assertThat(store.find(s1.id()).orElseThrow()
                .provider(McpProviderType.SAINT).orElseThrow().principalKey())
                .isEqualTo("student-A");
        assertThat(store.find(s2.id()).orElseThrow()
                .provider(McpProviderType.SAINT).orElseThrow().principalKey())
                .isEqualTo("student-B");
    }

    // --- transport session binding (ADR 0036 §1B) ---

    @Test
    void bindTransportId_allowsFindByTransportId() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.bindTransportId(session.id(), "transport-abc");

        Optional<McpAuthSession> found = store.findByTransportId("transport-abc");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(session.id());
    }

    @Test
    void bindTransportId_isIdempotent() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.bindTransportId(session.id(), "transport-abc");
        store.bindTransportId(session.id(), "transport-xyz"); // must not overwrite

        Optional<McpAuthSession> found = store.findByTransportId("transport-abc");
        assertThat(found).isPresent();
        assertThat(store.findByTransportId("transport-xyz")).isEmpty();
    }

    @Test
    void findByTransportId_expiredSessionReturnsEmpty() {
        McpAuthSessionStore store = store(T0, Duration.ofSeconds(1));
        McpAuthSession session = store.create();
        store.bindTransportId(session.id(), "transport-abc");

        // Advance past expiry
        McpAuthSessionStore futureStore = store(T0.plus(Duration.ofSeconds(10)), Duration.ofHours(4));
        assertThat(futureStore.findByTransportId("transport-abc")).isEmpty();
    }

    // --- OAuth subject binding (ADR 0036 §1A) ---

    @Test
    void bindOauthSubject_allowsFindByOauthSubject() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.bindOauthSubject(session.id(), "google-sub-12345");

        Optional<McpAuthSession> found = store.findByOauthSubject("google-sub-12345");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(session.id());
    }

    @Test
    void bindOauthSubject_isIdempotent() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));
        McpAuthSession session = store.create();

        store.bindOauthSubject(session.id(), "google-sub-12345");
        store.bindOauthSubject(session.id(), "google-sub-other"); // must not overwrite

        Optional<McpAuthSession> found = store.findByOauthSubject("google-sub-12345");
        assertThat(found).isPresent();
        assertThat(store.findByOauthSubject("google-sub-other")).isEmpty();
    }

    @Test
    void findByOauthSubject_expiredSessionReturnsEmpty() {
        McpAuthSessionStore store = store(T0, Duration.ofSeconds(1));
        McpAuthSession session = store.create();
        store.bindOauthSubject(session.id(), "google-sub-12345");

        McpAuthSessionStore futureStore = store(T0.plus(Duration.ofSeconds(10)), Duration.ofHours(4));
        assertThat(futureStore.findByOauthSubject("google-sub-12345")).isEmpty();
    }

    private McpAuthSessionStore store(Instant now, Duration sessionTtl) {
        return store(Clock.fixed(now, ZoneOffset.UTC), sessionTtl);
    }

    private McpAuthSessionStore store(Clock clock, Duration sessionTtl) {
        return new McpAuthSessionStore(repository, objectMapper, properties(sessionTtl), clock);
    }

    private static McpAuthProperties properties(Duration sessionTtl) {
        McpAuthProperties props = new McpAuthProperties();
        props.setSessionTtl(sessionTtl);
        return props;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration delta) {
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
