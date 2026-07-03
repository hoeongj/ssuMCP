package com.ssuai.domain.campus.dto;

import java.util.List;

/**
 * Wrapper for the public academic-calendar REST endpoint. A record envelope
 * (not a bare {@code List}) so the resolved year travels with the events and
 * additive fields never break existing clients.
 */
public record AcademicCalendarResponse(
        int year,
        List<AcademicCalendarEvent> events) {

    public AcademicCalendarResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
