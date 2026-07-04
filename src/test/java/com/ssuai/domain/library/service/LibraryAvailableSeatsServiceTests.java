package com.ssuai.domain.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.dto.LibraryAllAvailableSeatsResponse;
import com.ssuai.domain.library.dto.LibraryRoomAvailableSeatsResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.global.exception.LibraryAuthRequiredException;

class LibraryAvailableSeatsServiceTests {

    @Test
    void roomAvailabilityUsesRoomSeatCacheAndComputesCounts() {
        LibraryRoomSeatCache cache = mock(LibraryRoomSeatCache.class);
        LibraryAvailableSeatsService service =
                new LibraryAvailableSeatsService(cache, mock(LibrarySessionStore.class), "mock");
        when(cache.get(eq(57), isNull())).thenReturn(stubSeats());

        LibraryRoomAvailableSeatsResponse response = service.getRoomAvailableSeats(57, "session");

        assertThat(response.roomId()).isEqualTo(57);
        assertThat(response.totalSeats()).isEqualTo(4);
        assertThat(response.availableSeats()).isEqualTo(1);
        assertThat(response.occupiedSeats()).isEqualTo(1);
        assertThat(response.awaySeats()).isEqualTo(1);
        assertThat(response.inactiveSeats()).isEqualTo(1);
        verify(cache).get(57, null);
    }

    @Test
    void allAvailabilityUsesCacheForEveryRoom() {
        LibraryRoomSeatCache cache = mock(LibraryRoomSeatCache.class);
        LibraryAvailableSeatsService service =
                new LibraryAvailableSeatsService(cache, mock(LibrarySessionStore.class), "mock");
        when(cache.get(anyInt(), isNull())).thenReturn(stubSeats());

        LibraryAllAvailableSeatsResponse response = service.getAllAvailableSeats("session");

        assertThat(response.rooms()).hasSize(LibraryAvailableSeatsService.ALL_ROOM_IDS.size());
        assertThat(response.totalAvailableSeats()).isEqualTo(LibraryAvailableSeatsService.ALL_ROOM_IDS.size());
        assertThat(response.totalAwaySeats()).isEqualTo(LibraryAvailableSeatsService.ALL_ROOM_IDS.size());
        for (int roomId : LibraryAvailableSeatsService.ALL_ROOM_IDS) {
            verify(cache).get(roomId, null);
        }
    }

    @Test
    void allAvailabilityPropagatesTheOriginalExceptionWhenARoomFails() {
        LibraryRoomSeatCache cache = mock(LibraryRoomSeatCache.class);
        LibraryAvailableSeatsService service =
                new LibraryAvailableSeatsService(cache, mock(LibrarySessionStore.class), "mock");
        when(cache.get(anyInt(), isNull())).thenReturn(stubSeats());
        when(cache.get(eq(57), isNull())).thenThrow(new IllegalStateException("pyxis down"));

        // The concurrent fan-out must surface the ORIGINAL exception, not the
        // CompletionException wrapper that CompletableFuture.join() would throw.
        assertThatThrownBy(() -> service.getAllAvailableSeats("session"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("pyxis down");
    }

    @Test
    void realModePassesResolvedTokenToCache() {
        LibraryRoomSeatCache cache = mock(LibraryRoomSeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        LibraryAvailableSeatsService service = new LibraryAvailableSeatsService(cache, store, "real");
        when(store.token("session")).thenReturn(Optional.of("token"));
        when(cache.get(eq(57), eq("token"))).thenReturn(stubSeats());

        service.getRoomAvailableSeats(57, "session");

        verify(cache).get(57, "token");
    }

    @Test
    void realModeWithoutTokenThrowsBeforeCacheLookup() {
        LibraryRoomSeatCache cache = mock(LibraryRoomSeatCache.class);
        LibrarySessionStore store = mock(LibrarySessionStore.class);
        LibraryAvailableSeatsService service = new LibraryAvailableSeatsService(cache, store, "real");
        when(store.token("session")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRoomAvailableSeats(57, "session"))
                .isInstanceOf(LibraryAuthRequiredException.class);
        verifyNoInteractions(cache);
    }

    private static List<PyxisSeatInfo> stubSeats() {
        return List.of(
                new PyxisSeatInfo(5701, "1", "일반", "available", 0, 0),
                new PyxisSeatInfo(5702, "2", "일반", "occupied", 0, 60),
                new PyxisSeatInfo(5703, "3", "일반", "away", 5, 60),
                new PyxisSeatInfo(5704, "4", "일반", "inactive", 0, 0)
        );
    }
}
