package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpAuthStateStoreTests {

    private static final Instant T0 = Instant.parse("2026-05-18T10:00:00Z");
    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("test-session-id");
    private static final Duration TTL = Duration.ofMinutes(10);

    private McpAuthStateRepository repository;
    private McpAuthStateStore store;

    @BeforeEach
    void setUp() {
        repository = mock(McpAuthStateRepository.class);
        McpAuthProperties props = new McpAuthProperties();
        props.setStateTtl(TTL);
        store = new McpAuthStateStore(repository, props, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void generatePersistsAndReturnsEntryWithCorrectExpiry() {
        McpAuthStateEntry entry = store.generate(SESSION_ID, McpProviderType.SAINT);

        assertThat(entry.mcpSessionId()).isEqualTo(SESSION_ID);
        assertThat(entry.provider()).isEqualTo(McpProviderType.SAINT);
        assertThat(entry.expiresAt()).isEqualTo(T0.plus(TTL));
        assertThat(entry.state()).isNotBlank();
        verify(repository).save(any(McpAuthStateEntity.class));
    }

    @Test
    void consumeReturnsEntryAndDeletesFromDb() {
        String state = "abc123";
        McpAuthStateEntity entity = new McpAuthStateEntity(
                state, SESSION_ID.value(), McpProviderType.LIBRARY.name(),
                T0.plus(TTL), T0);
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.of(entity));
        when(repository.deleteIfActive(eq(state), any())).thenReturn(1);

        Optional<McpAuthStateEntry> consumed = store.consume(state);

        assertThat(consumed).isPresent();
        assertThat(consumed.get().provider()).isEqualTo(McpProviderType.LIBRARY);
        verify(repository).deleteIfActive(eq(state), any());
    }

    @Test
    void consumedStateCannotBeReused() {
        String state = "abc123";
        McpAuthStateEntity entity = new McpAuthStateEntity(
                state, SESSION_ID.value(), McpProviderType.SAINT.name(),
                T0.plus(TTL), T0);
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.of(entity));
        // First claim wins (1 row deleted); a replay finds the row gone (0 deleted).
        when(repository.deleteIfActive(eq(state), any()))
                .thenReturn(1)
                .thenReturn(0);

        store.consume(state);

        assertThat(store.consume(state)).isEmpty();
        verify(repository, times(2)).deleteIfActive(eq(state), any());
    }

    @Test
    void concurrentLoserGetsEmptyEvenWhenFindObservedTheRow() {
        // Atomic-claim race: the find still sees the row, but a concurrent caller already
        // deleted it, so deleteIfActive returns 0 and this caller must get empty.
        String state = "raced-state";
        McpAuthStateEntity entity = new McpAuthStateEntity(
                state, SESSION_ID.value(), McpProviderType.SAINT.name(),
                T0.plus(TTL), T0);
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.of(entity));
        when(repository.deleteIfActive(eq(state), any())).thenReturn(0);

        assertThat(store.consume(state)).isEmpty();
        verify(repository).deleteIfActive(eq(state), any());
    }

    @Test
    void expiredStateIsRejectedByRepositoryQuery() {
        // Expired states are filtered in the repository query; the store
        // gets an empty Optional and returns empty without claiming.
        String state = "expired-state";
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.empty());

        assertThat(store.consume(state)).isEmpty();
        verify(repository, never()).deleteIfActive(any(), any());
    }

    @Test
    void consumeUnknownStateReturnsEmpty() {
        when(repository.findByStateAndExpiresAtAfter(any(), any())).thenReturn(Optional.empty());

        assertThat(store.consume("nonexistent-state")).isEmpty();
        verify(repository, never()).deleteIfActive(any(), any());
    }

    @Test
    void consumeBlankStateReturnsEmptyWithoutDbCall() {
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume("   ")).isEmpty();
        verify(repository, never()).findByStateAndExpiresAtAfter(any(), any());
    }

    @Test
    void peekReturnsEntryWithoutDeletingFromDb() {
        String state = "peek-state";
        McpAuthStateEntity entity = new McpAuthStateEntity(
                state, SESSION_ID.value(), McpProviderType.LMS.name(),
                T0.plus(TTL), T0);
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.of(entity));

        Optional<McpAuthStateEntry> peeked = store.peek(state);

        assertThat(peeked).isPresent();
        assertThat(peeked.get().provider()).isEqualTo(McpProviderType.LMS);
        verify(repository, never()).deleteById(any());
    }

    @Test
    void peekThenConsumeSucceeds() {
        String state = "reusable-state";
        McpAuthStateEntity entity = new McpAuthStateEntity(
                state, SESSION_ID.value(), McpProviderType.LIBRARY.name(),
                T0.plus(TTL), T0);
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.of(entity));

        when(repository.deleteIfActive(eq(state), any())).thenReturn(1);

        Optional<McpAuthStateEntry> peeked = store.peek(state);
        Optional<McpAuthStateEntry> consumed = store.consume(state);

        assertThat(peeked).isPresent();
        assertThat(consumed).isPresent();
        verify(repository, times(1)).deleteIfActive(eq(state), any());
    }

    @Test
    void peekBlankStateReturnsEmptyWithoutDbCall() {
        assertThat(store.peek(null)).isEmpty();
        assertThat(store.peek("")).isEmpty();
        verify(repository, never()).findByStateAndExpiresAtAfter(any(), any());
    }

    @Test
    void consumeForWrongProviderStillReturnsEntry() {
        // Provider mismatch check is the callback controller's responsibility.
        String state = "saint-state";
        McpAuthStateEntity entity = new McpAuthStateEntity(
                state, SESSION_ID.value(), McpProviderType.SAINT.name(),
                T0.plus(TTL), T0);
        when(repository.findByStateAndExpiresAtAfter(eq(state), any())).thenReturn(Optional.of(entity));
        when(repository.deleteIfActive(eq(state), any())).thenReturn(1);

        Optional<McpAuthStateEntry> consumed = store.consume(state);

        assertThat(consumed).isPresent();
        assertThat(consumed.get().provider()).isEqualTo(McpProviderType.SAINT);
    }

    @Test
    void multipleGeneratesProduceDifferentStateTokens() {
        McpAuthStateEntry a = store.generate(SESSION_ID, McpProviderType.SAINT);
        McpAuthStateEntry b = store.generate(SESSION_ID, McpProviderType.LMS);

        assertThat(a.state()).isNotEqualTo(b.state());
    }
}
