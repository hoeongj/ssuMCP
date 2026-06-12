package com.ssuai.domain.library.timeseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogEntry;
import com.ssuai.domain.library.recommendation.LibrarySeatRoomCatalogService;
import com.ssuai.domain.library.service.LibraryRoomSeatCache;

class LibrarySeatSampleSamplerTests {

    @Test
    void readsRoomsFromCatalogThroughCacheAndBatchInsertsOneRowPerSeat() {
        LibrarySeatRoomCatalogService roomCatalogService = mock(LibrarySeatRoomCatalogService.class);
        LibraryRoomSeatCache roomSeatCache = mock(LibraryRoomSeatCache.class);
        LibrarySeatSampleRepository repository = mock(LibrarySeatSampleRepository.class);
        LibrarySeatSampleProperties properties = new LibrarySeatSampleProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-06-12T10:15:30Z"), ZoneOffset.UTC);
        LibrarySeatSampleSampler sampler =
                new LibrarySeatSampleSampler(roomCatalogService, roomSeatCache, repository, properties, clock);

        when(roomCatalogService.rooms()).thenReturn(List.of(
                room("2F", 2, 54, "open-reading-2f", true),
                room("B1", -1, null, "basement-reading-b1", true),
                room("6F", 6, 58, "graduate-reading", false),
                room("2F", 2, 53, "soongsil-square-on", true)
        ));
        when(roomSeatCache.get(eq(53), isNull())).thenReturn(List.of(
                seat(5301, "1", "available"),
                seat(5302, "2", "occupied")
        ));
        when(roomSeatCache.get(eq(54), isNull())).thenReturn(List.of(
                seat(5401, "1", "away"),
                seat(5402, "2", "inactive"),
                seat(5403, "3", "closed")
        ));
        when(repository.insertBatch(anyList())).thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());

        int inserted = sampler.sampleAt(Instant.parse("2026-06-12T10:15:00Z"));

        assertThat(inserted).isEqualTo(5);
        verify(roomSeatCache).get(53, null);
        verify(roomSeatCache).get(54, null);
        verifyNoMoreInteractions(roomSeatCache);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LibrarySeatSample>> samples =
                ArgumentCaptor.forClass((Class) List.class);
        verify(repository).insertBatch(samples.capture());
        assertThat(samples.getValue())
                .extracting(LibrarySeatSample::sampledAt)
                .containsOnly(Instant.parse("2026-06-12T10:15:00Z"));
        assertThat(samples.getValue())
                .extracting(LibrarySeatSample::roomId)
                .containsExactly(53, 53, 54, 54, 54);
        assertThat(samples.getValue())
                .extracting(LibrarySeatSample::statusCode)
                .containsExactly("A", "O", "W", "I", "U");
    }

    private static LibrarySeatRoomCatalogEntry room(
            String floorCode,
            Integer floor,
            Integer roomId,
            String roomCode,
            boolean reservable) {
        return new LibrarySeatRoomCatalogEntry(
                floorCode,
                floor,
                roomId,
                roomCode,
                roomCode,
                "all",
                reservable,
                false,
                false,
                "1-2",
                List.of("general"),
                List.of("zone"),
                List.of(),
                List.of());
    }

    private static PyxisSeatInfo seat(int externalSeatId, String label, String status) {
        return new PyxisSeatInfo(externalSeatId, label, "general", status, 0, 0);
    }
}
