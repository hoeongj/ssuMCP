package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.saint.dto.CourseScheduleEntry;
import com.ssuai.domain.saint.dto.MeetingSlot;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.TermSchedule;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

class SaintScheduleServiceTests {

    private final SaintScheduleCache cache = mock(SaintScheduleCache.class);
    private final SaintScheduleService service = new SaintScheduleService(cache);

    @Test
    void delegatesToCacheForValidStudentId() {
        ScheduleResponse stub = new ScheduleResponse(2024, 2026, 1, List.of(
                new TermSchedule(2026, 1, List.of(
                        new CourseScheduleEntry("자료구조", "김교수", List.of(
                                new MeetingSlot(1, "월", 3, "10:30-11:45", "정보과학관 30100")))))));
        when(cache.get("20241234", null, null)).thenReturn(stub);

        ScheduleResponse result = service.fetchSchedule("20241234");

        assertThat(result).isSameAs(stub);
        verify(cache).get("20241234", null, null);
    }

    @Test
    void delegatesRequestedTermToCache() {
        ScheduleResponse stub = new ScheduleResponse(2024, 2026, 3, List.of(
                new TermSchedule(2026, 3, List.of())));
        when(cache.get("20241234", 2026, 3)).thenReturn(stub);

        ScheduleResponse result = service.fetchSchedule("20241234", 2026, 3);

        assertThat(result).isSameAs(stub);
        verify(cache).get("20241234", 2026, 3);
    }

    @Test
    void cacheSaintSessionExpiredPropagates() {
        when(cache.get("20241234", null, null)).thenThrow(new SaintSessionExpiredException());

        assertThatThrownBy(() -> service.fetchSchedule("20241234"))
                .isInstanceOf(SaintSessionExpiredException.class);
    }

    @Test
    void blankStudentIdRaisesUnauthorizedBeforeTouchingCache() {
        assertThatThrownBy(() -> service.fetchSchedule(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.fetchSchedule(""))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.fetchSchedule("   "))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void invalidTermArgumentsRaiseBeforeTouchingCache() {
        assertThatThrownBy(() -> service.fetchSchedule("20241234", 2026, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fetchSchedule("20241234", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fetchSchedule("20241234", 2026, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fetchSchedule("20241234", 2026, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
