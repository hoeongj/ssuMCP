package com.ssuai.domain.lms.dto;

/**
 * Single pending assignment or quiz returned by the canvas to_dos API.
 *
 * @param courseName name of the enrolling course (joined from courses response)
 * @param title      assignment / quiz title
 * @param type       component type string from canvas ("assignment", "quiz", etc.)
 * @param dueDate    ISO-8601 due datetime, or {@code null} if no deadline set
 */
public record AssignmentItem(
        String courseName,
        String title,
        String type,
        String dueDate
) {
}
