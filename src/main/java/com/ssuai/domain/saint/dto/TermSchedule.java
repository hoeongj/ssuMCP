package com.ssuai.domain.saint.dto;

import java.util.List;

/**
 * Timetable for one academic term. Term values follow u-SAINT's four-term
 * cycle: 1=spring, 2=summer, 3=fall, 4=winter.
 */
public record TermSchedule(
        int year,
        int term,
        List<CourseScheduleEntry> entries
) {

    public TermSchedule {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
