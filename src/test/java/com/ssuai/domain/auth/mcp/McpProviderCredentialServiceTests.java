package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.auth.saint.SaintSessionStore.SaintProviderSession;
import com.ssuai.domain.library.auth.LibrarySessionStore;

class McpProviderCredentialServiceTests {

    private SaintSessionStore saintSessions;
    private LmsSessionStore lmsSessions;
    private LibrarySessionStore librarySessions;
    private McpProviderCredentialService service;

    @BeforeEach
    void setUp() {
        saintSessions = mock(SaintSessionStore.class);
        lmsSessions = mock(LmsSessionStore.class);
        librarySessions = mock(LibrarySessionStore.class);
        service = new McpProviderCredentialService(
                saintSessions, lmsSessions, librarySessions);
    }

    @Test
    void errorCredentialIsLinkedButNotAvailable() {
        McpProviderLink link = link(McpProviderType.SAINT, "saint-owner");
        when(saintSessions.health("saint-owner")).thenReturn(Optional.of(snapshot(
                McpProviderHealth.ERROR, "UPSTREAM_UNAVAILABLE")));
        when(saintSessions.session("saint-owner"))
                .thenReturn(Optional.of(mock(SaintProviderSession.class)));

        McpProviderCredentialStatus status = service.status(link);

        assertThat(status.linked()).isTrue();
        assertThat(status.available()).isFalse();
        assertThat(status.health().health()).isEqualTo(McpProviderHealth.ERROR);
        verify(saintSessions).health("saint-owner");
        verify(saintSessions).session("saint-owner");
    }

    @Test
    void expiredCredentialIsNotAvailable() {
        McpProviderLink link = link(McpProviderType.LMS, "lms-owner");
        when(lmsSessions.health("lms-owner")).thenReturn(Optional.of(snapshot(
                McpProviderHealth.EXPIRED, "AUTH_REQUIRED")));

        McpProviderCredentialStatus status = service.status(link);

        assertThat(status.linked()).isFalse();
        assertThat(status.available()).isFalse();
        verify(lmsSessions, never()).session("lms-owner");
    }

    @Test
    void unknownCredentialCanMakeItsFirstProviderCall() {
        McpProviderLink link = link(McpProviderType.SAINT, "saint-owner");
        when(saintSessions.health("saint-owner"))
                .thenReturn(Optional.of(McpProviderHealthSnapshot.unknown(1)));
        when(saintSessions.session("saint-owner"))
                .thenReturn(Optional.of(mock(SaintProviderSession.class)));

        McpProviderCredentialStatus status = service.status(link);

        assertThat(status.linked()).isTrue();
        assertThat(status.available()).isTrue();
        assertThat(status.health().health()).isEqualTo(McpProviderHealth.UNKNOWN);
    }

    @Test
    void libraryCredentialRemainsAvailableWithoutActiveHealthProbe() {
        McpProviderLink link = link(McpProviderType.LIBRARY, "library-owner");
        when(librarySessions.has("library-owner")).thenReturn(true);

        McpProviderCredentialStatus status = service.status(link);

        assertThat(status.linked()).isTrue();
        assertThat(status.available()).isTrue();
        verify(librarySessions).has("library-owner");
    }

    private static McpProviderLink link(McpProviderType provider, String owner) {
        return new McpProviderLink(
                provider, owner, Instant.parse("2026-07-18T01:00:00Z"));
    }

    private static McpProviderHealthSnapshot snapshot(
            McpProviderHealth health, String failureCode) {
        return new McpProviderHealthSnapshot(
                health,
                Instant.parse("2026-07-18T01:00:00Z"),
                null,
                Instant.parse("2026-07-18T01:00:00Z"),
                failureCode,
                1);
    }
}
