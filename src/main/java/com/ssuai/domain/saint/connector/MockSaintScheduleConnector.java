package com.ssuai.domain.saint.connector;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScheduleEntry;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.TermSchedule;

/**
 * Synthesizes a plausible cumulative timetable without hitting
 * {@code ecc.ssu.ac.kr}. Active by default so prod, CI, and dev never
 * accidentally call the real upstream — flip to
 * {@code ssuai.connector.saint-schedule: real} once a deployment is
 * actually ready to consume per-user data.
 *
 * <p>Schedule contents are identical across the iterated terms; the
 * point of the mock is to exercise the multi-term response shape, not
 * to model realistic curriculum drift.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.saint-schedule",
        havingValue = "mock", matchIfMissing = true)
class MockSaintScheduleConnector implements SaintScheduleConnector {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    @Autowired
    MockSaintScheduleConnector() {
        this(Clock.systemUTC());
    }

    MockSaintScheduleConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies) {
        int enrollmentYear = SaintScheduleHelpers.parseEnrollmentYear(studentId);
        LocalDate today = LocalDate.now(clock.withZone(KST));
        int currentYear = SaintScheduleHelpers.academicYearFor(today);
        int currentTerm = SaintScheduleHelpers.termFor(today);

        List<ScheduleEntry> entries = sampleEntries();
        List<TermSchedule> terms = new ArrayList<>();
        // Walk PREV from (currentYear, currentTerm) back to (enrollmentYear,
        // 1학기). Matches the Real connector's hop sequence exactly so the
        // chat path, controller path, and any downstream cache see the
        // same shape regardless of which connector is active.
        int year = currentYear;
        int term = currentTerm;
        terms.add(new TermSchedule(year, term, entries));
        while (!(year <= enrollmentYear && term <= SaintScheduleHelpers.TERM_SPRING)) {
            SaintScheduleHelpers.TermPosition prev = SaintScheduleHelpers.previousTerm(year, term);
            year = prev.year();
            term = prev.term();
            terms.add(new TermSchedule(year, term, entries));
        }
        return new ScheduleResponse(enrollmentYear, currentYear, currentTerm, terms);
    }

    private static List<ScheduleEntry> sampleEntries() {
        return List.of(
                new ScheduleEntry(1, "월", 3, "10:30-11:45", "자료구조", "김교수",
                        "정보과학관 30100 (강의실A)"),
                new ScheduleEntry(2, "화", 3, "10:30-11:45", "알고리즘", "이교수",
                        "정보과학관 30200 (강의실B)"),
                new ScheduleEntry(3, "수", 3, "10:30-11:45", "자료구조", "김교수",
                        "정보과학관 30100 (강의실A)"),
                new ScheduleEntry(4, "목", 3, "10:30-11:45", "알고리즘", "이교수",
                        "정보과학관 30200 (강의실B)"),
                new ScheduleEntry(1, "월", 5, "13:30-14:45", "운영체제", "박교수",
                        "정보과학관 30300 (강의실C)"),
                new ScheduleEntry(2, "화", 5, "13:30-14:20", "채플", "최교수",
                        "한경직기념관 10000"),
                new ScheduleEntry(3, "수", 5, "13:30-14:45", "운영체제", "박교수",
                        "정보과학관 30300 (강의실C)")
        );
    }
}
