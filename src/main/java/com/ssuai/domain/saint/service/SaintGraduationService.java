package com.ssuai.domain.saint.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintChapelConnector;
import com.ssuai.domain.saint.connector.SaintGraduationConnector;
import com.ssuai.domain.saint.dto.ChapelInfo;
import com.ssuai.domain.saint.dto.GraduationRequirementItem;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class SaintGraduationService {

    private static final Logger log = LoggerFactory.getLogger(SaintGraduationService.class);
    // SSU requires freshmen to pass 6 semesters of chapel. Rusaint does not
    // populate this value in the graduation requirements API response.
    private static final float CHAPEL_REQUIRED_SEMESTERS = 6.0f;

    private final SaintGraduationConnector connector;
    private final SaintChapelConnector chapelConnector;
    private final SaintSessionStore sessionStore;

    public SaintGraduationService(
            SaintGraduationConnector connector,
            SaintChapelConnector chapelConnector,
            SaintSessionStore sessionStore) {
        this.connector = connector;
        this.chapelConnector = chapelConnector;
        this.sessionStore = sessionStore;
    }

    public GraduationStatus fetchGraduationRequirements(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        PortalCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(SaintSessionExpiredException::new);
        GraduationStatus raw = connector.fetchGraduationRequirements(studentId, cookies);
        return mergeChapelHistory(raw, studentId, cookies);
    }

    private GraduationStatus mergeChapelHistory(GraduationStatus status, String studentId, PortalCookies cookies) {
        List<GraduationRequirementItem> requirements = status.requirements();
        int chapelIndex = findChapelIndex(requirements);
        if (chapelIndex == -1) {
            return status;
        }
        GraduationRequirementItem chapel = requirements.get(chapelIndex);
        if (chapel.required() > 0 || chapel.completed() > 0) {
            return status; // rusaint already populated chapel data
        }

        int completedSemesters = countCompletedChapelSemesters(studentId, cookies, status.grade());
        if (completedSemesters == 0) {
            return status; // nothing to merge (chapel fetch failed or all semesters failed)
        }

        boolean satisfied = completedSemesters >= CHAPEL_REQUIRED_SEMESTERS;
        GraduationRequirementItem merged = new GraduationRequirementItem(
                chapel.name(), chapel.category(),
                CHAPEL_REQUIRED_SEMESTERS, completedSemesters, 0.0f, satisfied);

        List<GraduationRequirementItem> updated = new ArrayList<>(requirements);
        updated.set(chapelIndex, merged);

        boolean isGraduatable = updated.stream().allMatch(GraduationRequirementItem::satisfied);
        return new GraduationStatus(
                isGraduatable,
                status.studentName(), status.department(), status.grade(),
                status.completedPoints(), status.graduationPoints(),
                updated);
    }

    private int countCompletedChapelSemesters(String studentId, PortalCookies cookies, int grade) {
        ChapelInfo current;
        try {
            current = chapelConnector.fetchChapelInfo(studentId, cookies, null, null);
        } catch (Exception exception) {
            log.warn("chapel fetch failed for graduation merge: studentId={}", studentId, exception);
            return 0;
        }

        int currentYear = current.year();
        String currentSemester = current.semester();
        int entryYear = (grade > 0) ? currentYear - grade + 1 : currentYear;

        int completed = 0;
        for (int year = entryYear; year <= currentYear; year++) {
            for (String sem : List.of("1학기", "2학기")) {
                // Stop if we've reached beyond the current semester
                if (year == currentYear && "2학기".equals(sem) && "1학기".equals(currentSemester)) {
                    break;
                }
                ChapelInfo info;
                if (year == currentYear && sem.equals(currentSemester)) {
                    info = current;
                } else {
                    try {
                        info = chapelConnector.fetchChapelInfo(studentId, cookies, year, sem);
                    } catch (Exception exception) {
                        log.debug("chapel fetch skipped for graduation merge: year={} semester={}", year, sem);
                        continue;
                    }
                }
                if ("P".equals(info.result())) {
                    completed++;
                }
            }
        }
        return completed;
    }

    private static int findChapelIndex(List<GraduationRequirementItem> requirements) {
        for (int i = 0; i < requirements.size(); i++) {
            if (requirements.get(i).name().contains("채플")) {
                return i;
            }
        }
        return -1;
    }
}
