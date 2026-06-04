package com.ssuai.domain.saint.connector;

import java.time.LocalDate;

import com.ssuai.domain.saint.dto.TermSchedule;

/**
 * Shared helpers for schedule connectors (Real and Mock).
 *
 * <p>{@link #parseEnrollmentYear(String)} reads the leading four digits of
 * the SSU student id — a leave-of-absence or re-admission does not mutate
 * the prefix, so it is the stable anchor for "iterate back to the term
 * the student first enrolled in" (Task 16 spec §3.4).
 *
 * <p>SSU's u-SAINT cycles through <strong>four terms per academic year</strong>
 * in the order 1학기 → 여름학기 → 2학기 → 겨울학기 → next year's 1학기.
 * The four-term cycle is exposed as {@code term} integers 1..4 matching
 * the cycle position (1=1학기, 2=여름학기, 3=2학기, 4=겨울학기). The PREV
 * button {@code WDA7} steps one term backwards, including across the
 * year boundary — confirmed by the 2026-05-17 spike (PR for multi-term
 * nav). The original spec §3.4 assumption "WDA7 = previous year, term
 * unchanged" was wrong.
 */
final class SaintScheduleHelpers {

    static final int TERM_SPRING = 1;
    static final int TERM_SUMMER = 2;
    static final int TERM_FALL = 3;
    static final int TERM_WINTER = 4;
    private static final int TERMS_PER_YEAR = 4;

    private SaintScheduleHelpers() {
    }

    static int parseEnrollmentYear(String studentId) {
        if (studentId == null || studentId.length() < 4) {
            throw new IllegalArgumentException("studentId must contain at least 4 leading digits");
        }
        String prefix = studentId.substring(0, 4);
        try {
            int year = Integer.parseInt(prefix);
            if (year < 1900 || year > 2100) {
                throw new IllegalArgumentException("studentId prefix is not a plausible year: " + prefix);
            }
            return year;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("studentId prefix is not numeric: " + prefix, exception);
        }
    }

    /**
     * Heuristic "what term is this calendar date in" used by the Mock
     * connector to pick a plausible default term. The Real connector
     * does not call this — it reads the displayed (year, term) directly
     * from the u-SAINT response dropdowns. Boundaries:
     * Mar–Jun → 1학기, Jul–Aug → 여름학기, Sep–Dec → 2학기, Jan–Feb → 겨울학기.
     */
    static int termFor(LocalDate date) {
        int month = date.getMonthValue();
        if (month <= 2) {
            return TERM_WINTER;
        }
        if (month <= 6) {
            return TERM_SPRING;
        }
        if (month <= 8) {
            return TERM_SUMMER;
        }
        return TERM_FALL;
    }

    /**
     * The 1학기 of the calendar year a given date falls in. Mock-only
     * helper that turns "today" into the year label u-SAINT would
     * surface on its first GET. Mock pairs this with {@link #termFor(LocalDate)}
     * to derive a synthetic (currentYear, currentTerm) without hitting
     * the upstream.
     *
     * <p>SSU's 학년도 advances in March, but for mock purposes treating
     * January-February as still belonging to the previous calendar year's
     * 겨울학기 keeps the mock's cycle invariant ({@code year + term} is
     * monotonically non-decreasing) consistent with the Real connector's
     * iteration semantics.
     */
    static int academicYearFor(LocalDate date) {
        return date.getMonthValue() <= 2 ? date.getYear() - 1 : date.getYear();
    }

    /**
     * Step one position backward through the four-term cycle.
     * Implements the PREV ({@code WDA7}) button semantics confirmed by
     * the 2026-05-17 spike: 1학기 → 겨울학기 of the previous year, otherwise
     * decrement the term within the same year.
     */
    static TermPosition previousTerm(int year, int term) {
        if (term <= 0 || term > TERMS_PER_YEAR) {
            throw new IllegalArgumentException("term must be 1..4, got " + term);
        }
        if (term == TERM_SPRING) {
            return new TermPosition(year - 1, TERM_WINTER);
        }
        return new TermPosition(year, term - 1);
    }

    /** Cumulative number of cycle steps from (a) up to (b). */
    static int stepsBetween(int fromYear, int fromTerm, int toYear, int toTerm) {
        return (toYear - fromYear) * TERMS_PER_YEAR + (toTerm - fromTerm);
    }

    static String labelFor(int term) {
        return switch (term) {
            case TERM_SPRING -> "1학기";
            case TERM_SUMMER -> "여름학기";
            case TERM_FALL -> "2학기";
            case TERM_WINTER -> "겨울학기";
            default -> throw new IllegalArgumentException("unsupported term: " + term);
        };
    }

    /**
     * Maps the Korean u-SAINT term label back to the cycle integer.
     * Returns -1 for anything we don't recognize so callers can decide
     * whether to fall back to a heuristic.
     */
    static int termFromLabel(String label) {
        if (label == null) {
            return -1;
        }
        String trimmed = label.trim();
        return switch (trimmed) {
            case "1학기" -> TERM_SPRING;
            case "여름학기" -> TERM_SUMMER;
            case "2학기" -> TERM_FALL;
            case "겨울학기" -> TERM_WINTER;
            default -> -1;
        };
    }

    /** (year, term) pair returned by {@link #previousTerm(int, int)}. */
    record TermPosition(int year, int term) {
        TermSchedule with(java.util.List<com.ssuai.domain.saint.dto.CourseScheduleEntry> entries) {
            return new TermSchedule(year, term, entries);
        }
    }
}
