package com.ssuai.domain.saint.dto;

import java.util.List;

/**
 * Timetable for one academic term (예: 2025년 1학기). {@code term} is 1
 * for spring, 2 for fall; Task 16 PR 16b's first cut iterates only 1학기
 * across all enrolled years (spec §3.4), and {@code term=2} / 계절학기
 * (3, 4) become reachable when the corresponding nav buttons are spiked
 * in a follow-up.
 */
public record TermSchedule(
        int year,
        int term,
        List<ScheduleEntry> entries
) {

    public TermSchedule {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
