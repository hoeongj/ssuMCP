package com.ssuai.domain.saint.service;

import org.springframework.stereotype.Service;

import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

/**
 * Façade for the realtime u-SAINT timetable fetch (Task 16 PR 16b).
 *
 * <p>Per-student cumulative timetables flow through {@link SaintScheduleCache},
 * which folds in the portal-cookie lookup and applies a short LRU+TTL window
 * (spec §6 #5). The cache surfaces {@link SaintSessionExpiredException} when
 * a fresh fetch is needed but no cookies are stored — and the connector
 * raises the same exception from inside {@code fetchSchedule} when the
 * stored cookies turn out to be stale — so both paths converge on the same
 * 401 {@code SAINT_SESSION_EXPIRED} error code for the frontend to route
 * the user back to SmartID SSO.
 */
@Service
public class SaintScheduleService {

    private final SaintScheduleCache cache;

    public SaintScheduleService(SaintScheduleCache cache) {
        this.cache = cache;
    }

    public ScheduleResponse fetchSchedule(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        return cache.get(studentId);
    }
}
