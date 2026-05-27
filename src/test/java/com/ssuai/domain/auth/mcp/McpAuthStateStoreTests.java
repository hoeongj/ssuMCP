package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class McpAuthStateStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");
    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("test-session-id");

    @Test
    void generateAndConsumeSucceeds() {
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));

        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.SAINT);
        Optional<McpAuthStateEntry> consumed = store.consume(entry.state());

        assertThat(consumed).isPresent();
        assertThat(consumed.get().mcpSessionId()).isEqualTo(SESSION_ID);
        assertThat(consumed.get().provider()).isEqualTo(McpProviderType.SAINT);
        assertThat(store.size()).isZero();
    }

    @Test
    void consumedStateCannotBeReused() {
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));

        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.SAINT);
        store.consume(entry.state());

        assertThat(store.consume(entry.state())).isEmpty();
    }

    @Test
    void expiredStateIsRejected() {
        MutableClock clock = new MutableClock(T0);
        McpAuthStateStore store = store(clock, Duration.ofMinutes(10));

        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.LMS);
        clock.advance(Duration.ofMinutes(11));

        assertThat(store.consume(entry.state())).isEmpty();
    }

    @Test
    void expiredStateIsDroppedFromStore() {
        MutableClock clock = new MutableClock(T0);
        McpAuthStateStore store = store(clock, Duration.ofMinutes(10));

        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.SAINT);
        clock.advance(Duration.ofMinutes(11));
        store.consume(entry.state());

        assertThat(store.size()).isZero();
    }

    @Test
    void consumeForWrongProviderStillReturnsEntry() {
        // Provider mismatch check is the callback controller's responsibility,
        // not the state store's. The store returns the entry regardless of provider.
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));
        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.SAINT);

        Optional<McpAuthStateEntry> consumed = store.consume(entry.state());

        assertThat(consumed).isPresent();
        assertThat(consumed.get().provider()).isEqualTo(McpProviderType.SAINT);
        // caller checks entry.provider() != expected and rejects
    }

    @Test
    void consumeUnknownStateReturnsEmpty() {
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));
        assertThat(store.consume("nonexistent-state")).isEmpty();
    }

    @Test
    void consumeBlankStateReturnsEmpty() {
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume("   ")).isEmpty();
    }

    @Test
    void stateContainsCorrectExpiry() {
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));

        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.LIBRARY);

        assertThat(entry.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    void multipleProvidersProduceDifferentStates() {
        McpAuthStateStore store = store(T0, Duration.ofMinutes(10));

        McpAuthStateEntry saint = store.generate(SESSION_ID, McpProviderType.SAINT);
        McpAuthStateEntry lms = store.generate(SESSION_ID, McpProviderType.LMS);

        assertThat(saint.state()).isNotEqualTo(lms.state());
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void lruCapEvictsEldest() {
        McpAuthProperties props = properties(Duration.ofMinutes(10));
        props.setMaxStates(2);
        McpAuthStateStore store = new McpAuthStateStore(props, Clock.fixed(T0, ZoneOffset.UTC));

        McpAuthStateEntry a = store.generate(SESSION_ID, McpProviderType.SAINT);
        McpAuthStateEntry b = store.generate(SESSION_ID, McpProviderType.LMS);
        McpAuthStateEntry c = store.generate(SESSION_ID, McpProviderType.LIBRARY);

        // oldest entry evicted — state 'a' is gone
        assertThat(store.consume(a.state())).isEmpty();
        assertThat(store.consume(b.state())).isPresent();
        assertThat(store.consume(c.state())).isPresent();
    }

    private static McpAuthStateStore store(Instant now, Duration stateTtl) {
        return new McpAuthStateStore(properties(stateTtl), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static McpAuthStateStore store(MutableClock clock, Duration stateTtl) {
        return new McpAuthStateStore(properties(stateTtl), clock);
    }

    private static McpAuthProperties properties(Duration stateTtl) {
        McpAuthProperties props = new McpAuthProperties();
        props.setStateTtl(stateTtl);
        props.setMaxStates(1000);
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
