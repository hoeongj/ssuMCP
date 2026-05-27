package com.ssuai.domain.saint.connector;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;

/**
 * Fetches a student's cumulative u-SAINT timetable, keyed by their
 * authenticated SAINT session material. Implementations are
 * profile-switched via {@code ssuai.connector.saint-schedule}
 * (mock | real | rusaint).
 *
 * <p>An implementation MUST throw {@link SaintSessionExpiredException}
 * when the supplied session material no longer authenticates against
 * upstream SAINT. The service layer relies on that signal to surface a
 * 401 {@code SAINT_SESSION_EXPIRED} to the caller, who can then re-run
 * the SmartID SSO loop.
 */
public interface SaintScheduleConnector {

    ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies);
}
