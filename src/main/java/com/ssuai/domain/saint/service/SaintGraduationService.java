package com.ssuai.domain.saint.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintChapelConnector;
import com.ssuai.domain.saint.connector.SaintGraduationConnector;
import com.ssuai.domain.saint.dto.GraduationRequirementItem;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.global.exception.SaintSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class SaintGraduationService {

    private static final Logger log = LoggerFactory.getLogger(SaintGraduationService.class);

    private final SaintGraduationConnector connector;
    private final SaintChapelConnector chapelConnector;
    private final SaintSessionStore sessionStore;
    // SSU requires 6 chapel semesters for students entering as freshmen, but the
    // number differs for transfer students and can change by regulation, and
    // rusaint does not populate it in the graduation requirements API response —
    // so it is a config knob (ADR 0049) instead of a compile-time constant.
    private final float chapelRequiredSemesters;

    public SaintGraduationService(
            SaintGraduationConnector connector,
            SaintChapelConnector chapelConnector,
            SaintSessionStore sessionStore,
            @Value("${ssuai.saint.chapel.required-semesters:6.0}") float chapelRequiredSemesters) {
        this.connector = connector;
        this.chapelConnector = chapelConnector;
        this.sessionStore = sessionStore;
        this.chapelRequiredSemesters = chapelRequiredSemesters;
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

        int completedSemesters;
        try {
            completedSemesters = countCompletedChapelSemesters(studentId, cookies, status.grade());
        } catch (Exception exception) {
            log.warn("chapel semester count failed for graduation merge: studentId={}",
                    SaintSessionStore.fingerprint(studentId), exception);
            return status; // connector threw — leave 0/0 unchanged
        }
        // completedSemesters is authoritative: 0 means genuinely zero passed, not a fetch failure

        boolean satisfied = completedSemesters >= chapelRequiredSemesters;
        GraduationRequirementItem merged = new GraduationRequirementItem(
                chapel.name(), chapel.category(),
                chapelRequiredSemesters, completedSemesters, 0.0f, satisfied);

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
        int currentYear = java.time.LocalDate.now().getYear();
        int entryYear = (grade > 0) ? currentYear - grade + 1 : currentYear;
        // Let exceptions propagate — caller (mergeChapelHistory) handles them.
        return chapelConnector.countChapelPassedSemesters(studentId, cookies, entryYear);
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
