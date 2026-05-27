package com.ssuai.domain.saint.dto;

import java.util.List;

/**
 * Cumulative timetable across every term the student has enrolled in,
 * keyed by the {@code studentId} that drove the request. Returned by
 * {@code GET /api/saint/schedule} and the {@code get_my_schedule} MCP
 * tool.
 *
 * <p>{@code enrollmentYear} is derived from the student id's leading
 * four digits (학번 substring(0,4)) — even a leave-of-absence or
 * re-admission does not alter that prefix, so the iterate window stays
 * stable. {@code currentYear} / {@code currentTerm} let the frontend
 * highlight the most recent term without re-deriving from the list.
 */
public record ScheduleResponse(
        int enrollmentYear,
        int currentYear,
        int currentTerm,
        List<TermSchedule> terms
) {

    public ScheduleResponse {
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
