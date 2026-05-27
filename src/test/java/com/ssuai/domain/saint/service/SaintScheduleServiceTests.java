package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.saint.dto.ScheduleEntry;
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
                        new ScheduleEntry(1, "월", 3, "10:30-11:45",
                                "자료구조", "김교수", "정보과학관 30100")))));
        when(cache.get("20241234")).thenReturn(stub);

        ScheduleResponse result = service.fetchSchedule("20241234");

        assertThat(result).isSameAs(stub);
        verify(cache).get("20241234");
    }

    @Test
    void cacheSaintSessionExpiredPropagates() {
        when(cache.get("20241234")).thenThrow(new SaintSessionExpiredException());

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
}
