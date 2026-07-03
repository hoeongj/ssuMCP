package com.ssuai.domain.campus.dto;

/**
 * One academic-calendar entry.
 *
 * @param date     ISO start date ({@code yyyy-MM-dd}); kept under its original
 *                 name so existing consumers (web client, MCP clients) do not
 *                 break — semantically the range start
 * @param endDate  ISO inclusive end date for multi-day entries ("MM.DD ~ MM.DD"
 *                 rows on the source page); {@code null} for single-day entries
 * @param event    entry title as published
 * @param category source category; empty for real data (the page carries none)
 */
public record AcademicCalendarEvent(
        String date,
        String endDate,
        String event,
        String category
) {
}
