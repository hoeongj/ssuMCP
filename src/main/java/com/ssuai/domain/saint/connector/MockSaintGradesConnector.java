package com.ssuai.domain.saint.connector;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.CourseGrade;
import com.ssuai.domain.saint.dto.GpaSummary;
import com.ssuai.domain.saint.dto.GradesResponse;
import com.ssuai.domain.saint.dto.TermGpa;

/**
 * Synthesizes plausible grades without hitting {@code ecc.ssu.ac.kr}.
 * Active by default so prod, CI, and dev never accidentally call the
 * real upstream — flip to {@code ssuai.connector.saint-grades: real}
 * once a deployment is actually ready to consume per-user data.
 *
 * <p>Generates one normal-letter-grade row per past term (입학년도 1학기
 * → 현재 학기 직전) and one P/F current term, mirroring the shape the
 * Real connector returns after a full prev iterate.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-grades",
        havingValue = "mock", matchIfMissing = true)
class MockSaintGradesConnector implements SaintGradesConnector {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    @Autowired
    MockSaintGradesConnector() {
        this(Clock.systemUTC());
    }

    MockSaintGradesConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public GradesResponse fetchGrades(String studentId, PortalCookies cookies) {
        int enrollmentYear = parseEnrollmentYear(studentId);
        LocalDate today = LocalDate.now(clock.withZone(KST));
        int currentYear = today.getYear();
        int currentTerm = today.getMonthValue() <= 8 ? 1 : 2;

        List<TermGpa> history = new ArrayList<>();
        Map<String, List<CourseGrade>> details = new LinkedHashMap<>();

        // Most recent term first (page order) — current term modeled as
        // P/F-only so the default-empty bottom table case is exercised.
        TermGpa currentRow = new TermGpa(currentYear, termLabel(currentTerm),
                3.0d, 3.0d, 3.0d, 0.0d, 0.0d, 0.0d,
                "0/0", "0/0", false, false, false);
        history.add(currentRow);

        // Walk every prior term from (currentYear-1)-2학기 back to
        // enrollmentYear-1학기. Each gets a normal-letter-grade history
        // row plus a matching detail list (as if the prev iterate had
        // walked back through it).
        for (int year = currentYear - 1; year >= enrollmentYear; year--) {
            for (int term = 2; term >= 1; term--) {
                TermGpa row = new TermGpa(year, termLabel(term),
                        18.0d, 18.0d, 3.0d, 3.50d, 63.00d, 85.00d,
                        "50/100", "60/100", false, false, false);
                history.add(row);
                details.put(row.termKey(), sampleCourses());
            }
        }

        GpaSummary academicRecord = new GpaSummary(75.0d, 75.0d, 262.50d, 3.50d, 85.00d, 12.0d);
        GpaSummary certificate = new GpaSummary(72.0d, 72.0d, 252.00d, 3.50d, 85.00d, 12.0d);
        return new GradesResponse(history, academicRecord, certificate, details);
    }

    private static int parseEnrollmentYear(String studentId) {
        if (studentId == null || studentId.length() < 4) {
            return LocalDate.now().getYear();
        }
        try {
            return Integer.parseInt(studentId.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return LocalDate.now().getYear();
        }
    }

    private static String termLabel(int term) {
        return term == 1 ? "1학기" : "2학기";
    }

    private static List<CourseGrade> sampleCourses() {
        return List.of(
                new CourseGrade("95", "A0", "과목A", "21500001", 3.0d, "김교수", ""),
                new CourseGrade("88", "B+", "과목B", "21500002", 3.0d, "이교수", ""),
                new CourseGrade("P", "P", "비전채플", "21500003", 0.5d, "박교수", ""));
    }
}
