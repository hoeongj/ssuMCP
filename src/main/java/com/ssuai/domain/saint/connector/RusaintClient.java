package com.ssuai.domain.saint.connector;

import java.util.List;

import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

public interface RusaintClient {

    /**
     * Eagerly loads and verifies the native rusaint FFI so the first real login
     * doesn't pay the one-time native-load cost. Default is a no-op (test doubles
     * and mock connectors have no native layer to warm). See
     * {@code RusaintUniFfiClient#warmUp()} for why this matters to login latency.
     */
    default void warmUp() {
        // no-op by default
    }

    RusaintAuthenticatedSession authenticateWithToken(String studentId, String ssoToken);

    ScheduleResponse fetchSchedule(String studentId, String sessionJson);

    default ScheduleResponse fetchSchedule(String studentId, String sessionJson, Integer year, Integer term) {
        if (year == null && term == null) {
            return fetchSchedule(studentId, sessionJson);
        }
        throw new UnsupportedOperationException("term-specific schedule fetch is not supported");
    }

    GradesResponse fetchGrades(String studentId, String sessionJson);

    ChapelInfo fetchChapelInfo(String studentId, String sessionJson);

    ChapelInfo fetchChapelInfo(String studentId, String sessionJson, Integer year, String semester);

    int countChapelPassedSemesters(String studentId, String sessionJson, int entryYear);

    GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson);

    List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson);
}
