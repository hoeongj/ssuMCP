package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class McpAuthSessionStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");

    @Test
    void createReturnsSessionWithId() {
        McpAuthSessionStore store = store(T0, Duration.ofHours(4));

        McpAuthSession session = store.create();

        assertThat(session.id()).isNotNull();
        assertThat(session.id().value()).isNotBlank();
        assertThat(session.providers()).isEmpty();
        assertThat(session.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(4)));
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
    void lruCapEvictsEldestAccessedEntry() {
        McpAuthProperties props = properties(Duration.ofHours(4));
        props.setMaxSessions(3);
        McpAuthSessionStore store = new McpAuthSessionStore(props, Clock.fixed(T0, ZoneOffset.UTC));

        McpAuthSession a = store.create();
        McpAuthSession b = store.create();
        McpAuthSession c = store.create();
        // touch 'a' so 'b' is the least-recently-accessed
        store.find(a.id());
        McpAuthSession d = store.create();

        assertThat(store.find(a.id())).isPresent();
        assertThat(store.find(b.id())).isEmpty();
        assertThat(store.find(c.id())).isPresent();
        assertThat(store.find(d.id())).isPresent();
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

    private static McpAuthSessionStore store(Instant now, Duration sessionTtl) {
        return new McpAuthSessionStore(properties(sessionTtl), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static McpAuthSessionStore store(MutableClock clock, Duration sessionTtl) {
        return new McpAuthSessionStore(properties(sessionTtl), clock);
    }

    private static McpAuthProperties properties(Duration sessionTtl) {
        McpAuthProperties props = new McpAuthProperties();
        props.setSessionTtl(sessionTtl);
        props.setMaxSessions(500);
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
