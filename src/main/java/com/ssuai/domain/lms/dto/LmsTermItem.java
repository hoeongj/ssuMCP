package com.ssuai.domain.lms.dto;

/**
 * A single Canvas LMS enrollment term.
 *
 * @param id          Canvas internal term ID (use this in term_id parameters)
 * @param name        human-readable name, e.g. "2026 1학기"
 * @param startAt     ISO-8601 start date/time, null if the API does not return it
 * @param endAt       ISO-8601 end date/time, null if the API does not return it
 * @param defaultTerm true when the Canvas API marks this as the current default term
 */
public record LmsTermItem(long id, String name, String startAt, String endAt, boolean defaultTerm) {
}
