package com.ssuai.domain.lms.dto;

import java.util.List;

/**
 * Pending assignments and quizzes for the current LMS term.
 *
 * @param termId the canvas term id used to query this response
 * @param items  flat list of pending todo items (may be empty if nothing is due)
 */
public record AssignmentsResponse(
        long termId,
        List<AssignmentItem> items
) {
}
