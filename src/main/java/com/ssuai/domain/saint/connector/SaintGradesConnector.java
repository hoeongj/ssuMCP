package com.ssuai.domain.saint.connector;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;

/**
 * Fetches a student's cumulative u-SAINT grades, keyed by their authenticated
 * SAINT session material. Implementations are profile-switched via
 * {@code ssuai.connector.saint-grades} (mock | real | rusaint).
 *
 * <p>An implementation MUST throw {@link SaintSessionExpiredException}
 * when the supplied session material no longer authenticates against
 * upstream SAINT, same contract as {@link SaintScheduleConnector}.
 */
public interface SaintGradesConnector {

    GradesResponse fetchGrades(String studentId, PortalCookies cookies);
}
