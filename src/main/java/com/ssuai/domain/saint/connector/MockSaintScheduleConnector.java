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
import com.ssuai.domain.saint.dto.CourseScheduleEntry;
import com.ssuai.domain.saint.dto.MeetingSlot;
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
        return fetchSchedule(studentId, cookies, null, null);
    }

    @Override
    public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies, Integer requestedYear, Integer requestedTerm) {
        int enrollmentYear = SaintScheduleHelpers.parseEnrollmentYear(studentId);
        LocalDate today = LocalDate.now(clock.withZone(KST));
        int currentYear = requestedYear == null ? SaintScheduleHelpers.academicYearFor(today) : requestedYear;
        int currentTerm = requestedTerm == null ? SaintScheduleHelpers.termFor(today) : requestedTerm;
        if (currentTerm < SaintScheduleHelpers.TERM_SPRING || currentTerm > SaintScheduleHelpers.TERM_WINTER) {
            throw new IllegalArgumentException("term must be 1..4");
        }

        List<CourseScheduleEntry> entries = sampleEntries();
        if (requestedYear != null) {
            return new ScheduleResponse(enrollmentYear, currentYear, currentTerm,
                    List.of(new TermSchedule(currentYear, currentTerm, entries)));
        }

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

    private static List<CourseScheduleEntry> sampleEntries() {
        return List.of(
                new CourseScheduleEntry("자료구조", "김교수", List.of(
                        new MeetingSlot(1, "월", 3, "10:30-11:45", "정보과학관 30100 (강의실A)"),
                        new MeetingSlot(3, "수", 3, "10:30-11:45", "정보과학관 30100 (강의실A)")
                )),
                new CourseScheduleEntry("알고리즘", "이교수", List.of(
                        new MeetingSlot(2, "화", 3, "10:30-11:45", "정보과학관 30200 (강의실B)"),
                        new MeetingSlot(4, "목", 3, "10:30-11:45", "정보과학관 30200 (강의실B)")
                )),
                new CourseScheduleEntry("운영체제", "박교수", List.of(
                        new MeetingSlot(1, "월", 5, "13:30-14:45", "정보과학관 30300 (강의실C)"),
                        new MeetingSlot(3, "수", 5, "13:30-14:45", "정보과학관 30300 (강의실C)")
                )),
                new CourseScheduleEntry("채플", "최교수", List.of(
                        new MeetingSlot(2, "화", 5, "13:30-14:20", "한경직기념관 10000")
                ))
        );
    }
}
