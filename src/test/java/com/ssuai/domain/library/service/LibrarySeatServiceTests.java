package com.ssuai.domain.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.timeseries.LibrarySamplerSessionManager;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibrarySeatServiceTests {

    private static final String SESSION_KEY = "test-session-id";
    private static final String TOKEN = "stub-ssotoken-value";
    private static final String SAMPLER_TOKEN = "sampler-ssotoken-value";

    @Test
    void connectorExceptionBubblesUpWithoutWrapping() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        when(cache.get(eq(LibraryFloor.F2), any())).thenThrow(new ConnectorTimeoutException());
        LibrarySeatService service = mockModeService(cache, mock(LibrarySessionStore.class));

        assertThatThrownBy(() -> service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    @Test
    void mockModeSkipsSessionCheck() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(cache.get(eq(LibraryFloor.F2), any())).thenReturn(stub);
        LibrarySeatService service = new LibrarySeatService(cache, store, mockSampler(), "mock");

        assertThat(service.isAuthRequired()).isFalse();
        assertThat(service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY)).isSameAs(stub);
    }

    @Test
    void realModeWithoutSessionThrows401() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        when(store.token(SESSION_KEY)).thenReturn(Optional.empty());
        LibrarySeatService service = new LibrarySeatService(cache, store, mockSampler(), "real");

        assertThat(service.isAuthRequired()).isTrue();
        assertThatThrownBy(() -> service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .isInstanceOf(LibraryAuthRequiredException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void realModeWithCapturedSessionDelegatesToCache() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        when(store.token(SESSION_KEY)).thenReturn(Optional.of(TOKEN));
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(cache.get(eq(LibraryFloor.F2), any())).thenReturn(stub);
        LibrarySeatService service = new LibrarySeatService(cache, store, mockSampler(), "real");

        assertThat(service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY)).isSameAs(stub);
    }

    @Test
    void publicSeatStatusUsesSamplerTokenInRealMode() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        LibrarySamplerSessionManager sampler = mockSampler();
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(sampler.currentToken()).thenReturn(Optional.of(SAMPLER_TOKEN));
        when(cache.get(LibraryFloor.F2, SAMPLER_TOKEN)).thenReturn(stub);
        LibrarySeatService service = new LibrarySeatService(cache, store, sampler, "real");

        assertThat(service.getPublicSeatStatus(LibraryFloor.F2)).isSameAs(stub);
        verify(cache).get(LibraryFloor.F2, SAMPLER_TOKEN);
        verifyNoInteractions(store);
    }

    @Test
    void publicSeatStatusRefreshesRejectedSamplerTokenOnce() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        LibrarySamplerSessionManager sampler = mockSampler();
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(sampler.currentToken()).thenReturn(Optional.of("expired-token"));
        when(sampler.loginForRun()).thenReturn(Optional.of(SAMPLER_TOKEN));
        when(cache.get(LibraryFloor.F2, "expired-token")).thenThrow(new LibraryAuthRequiredException());
        when(cache.get(LibraryFloor.F2, SAMPLER_TOKEN)).thenReturn(stub);
        LibrarySeatService service = new LibrarySeatService(cache, store, sampler, "real");

        assertThat(service.getPublicSeatStatus(LibraryFloor.F2)).isSameAs(stub);
        verify(sampler).invalidateToken();
        verify(sampler).loginForRun();
        verifyNoInteractions(store);
    }

    private static LibrarySeatService mockModeService(LibrarySeatCache cache, LibrarySessionStore store) {
        return new LibrarySeatService(cache, store, mockSampler(), "mock");
    }

    private static LibrarySamplerSessionManager mockSampler() {
        return mock(LibrarySamplerSessionManager.class);
    }

    private static LibrarySeatStatusResponse stubResponse(LibraryFloor floor) {
        return new LibrarySeatStatusResponse(
                floor.code(),
                floor.displayLabel(),
                36,
                12,
                18,
                6,
                Instant.parse("2026-05-15T10:00:00Z"),
                List.of()
        );
    }
}
