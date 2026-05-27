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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ssuai.domain.library.auth.LibrarySessionProperties;
import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibrarySeatServiceTests {

    private static final String SESSION_KEY = "test-session-id";
    private static final String TOKEN = "stub-ssotoken-value";

    @Test
    void delegatesToCacheForRequestedFloor() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(cache.get(eq(LibraryFloor.F2), any())).thenReturn(stub);
        LibrarySeatService service = mockModeService(cache, new LibrarySessionStore(defaultProperties()));

        LibrarySeatStatusResponse response = service.getSeatStatus(LibraryFloor.F2);

        assertThat(response).isSameAs(stub);
        ArgumentCaptor<LibraryFloor> floorCaptor = ArgumentCaptor.forClass(LibraryFloor.class);
        verify(cache).get(floorCaptor.capture(), any());
        assertThat(floorCaptor.getValue()).isEqualTo(LibraryFloor.F2);
    }

    @Test
    void connectorExceptionBubblesUpWithoutWrapping() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        when(cache.get(eq(LibraryFloor.F2), any())).thenThrow(new ConnectorTimeoutException());
        LibrarySeatService service = mockModeService(cache, new LibrarySessionStore(defaultProperties()));

        assertThatThrownBy(() -> service.getSeatStatus(LibraryFloor.F2))
                .isInstanceOf(ConnectorTimeoutException.class);
    }

    @Test
    void mockModeSkipsSessionCheck() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = new LibrarySessionStore(defaultProperties());
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(cache.get(eq(LibraryFloor.F2), any())).thenReturn(stub);
        LibrarySeatService service = new LibrarySeatService(cache, store, "mock");

        assertThat(service.isAuthRequired()).isFalse();
        assertThat(service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY)).isSameAs(stub);
    }

    @Test
    void realModeWithoutSessionThrows401() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = new LibrarySessionStore(defaultProperties());
        LibrarySeatService service = new LibrarySeatService(cache, store, "real");

        assertThat(service.isAuthRequired()).isTrue();
        assertThatThrownBy(() -> service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY))
                .isInstanceOf(LibraryAuthRequiredException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void realModeWithCapturedSessionDelegatesToCache() {
        LibrarySeatCache cache = mock(LibrarySeatCache.class);
        LibrarySessionStore store = new LibrarySessionStore(defaultProperties());
        store.put(SESSION_KEY, TOKEN);
        LibrarySeatStatusResponse stub = stubResponse(LibraryFloor.F2);
        when(cache.get(eq(LibraryFloor.F2), any())).thenReturn(stub);
        LibrarySeatService service = new LibrarySeatService(cache, store, "real");

        assertThat(service.getSeatStatusForSession(LibraryFloor.F2, SESSION_KEY)).isSameAs(stub);
    }

    private static LibrarySeatService mockModeService(LibrarySeatCache cache, LibrarySessionStore store) {
        return new LibrarySeatService(cache, store, "mock");
    }

    private static LibrarySessionProperties defaultProperties() {
        return new LibrarySessionProperties();
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
