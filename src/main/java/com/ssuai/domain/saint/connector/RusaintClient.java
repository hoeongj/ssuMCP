package com.ssuai.domain.saint.connector;

import java.util.List;

import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

public interface RusaintClient {

    RusaintAuthenticatedSession authenticateWithToken(String studentId, String ssoToken);

    ScheduleResponse fetchSchedule(String studentId, String sessionJson);

    GradesResponse fetchGrades(String studentId, String sessionJson);

    ChapelInfo fetchChapelInfo(String studentId, String sessionJson);

    ChapelInfo fetchChapelInfo(String studentId, String sessionJson, Integer year, String semester);

    GraduationStatus fetchGraduationRequirements(String studentId, String sessionJson);

    List<ScholarshipEntry> fetchScholarships(String studentId, String sessionJson);
}
