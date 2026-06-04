package com.ssuai.domain.saint.dto;

import java.util.List;

/**
 * One course in a term timetable, grouped across all weekly meeting slots.
 */
public record CourseScheduleEntry(
        String course,
        String professor,
        List<MeetingSlot> meetings
) {

    public CourseScheduleEntry {
        meetings = meetings == null ? List.of() : List.copyOf(meetings);
    }
}
