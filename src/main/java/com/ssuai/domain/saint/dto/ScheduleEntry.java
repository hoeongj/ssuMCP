package com.ssuai.domain.saint.dto;

/**
 * One cell of a weekly timetable — the (day, period) intersection
 * occupied by a single lecture. {@code dayOfWeek} follows the ISO
 * convention (1 = Monday … 7 = Sunday). The u-SAINT timetable page
 * never returns Sunday cells, so {@code dayOfWeek} in practice is 1..6.
 *
 * <p>{@code period} is the SSU "교시" number (1..10). {@code timeRange}
 * mirrors the visible label exactly (e.g. {@code "10:30-11:45"}); we
 * intentionally keep it as a string rather than typed {@code LocalTime}
 * boundaries because chapel and other special slots use non-standard
 * durations and the chatbot/UI consume the label verbatim.
 *
 * <p>{@code room} is the unmodified u-SAINT label (e.g. "정보과학관 30100
 * (강의실A)"); the parenthetical alias is part of the data and not
 * stripped.
 */
public record ScheduleEntry(
        int dayOfWeek,
        String dayLabel,
        int period,
        String timeRange,
        String course,
        String professor,
        String room
) {
}
