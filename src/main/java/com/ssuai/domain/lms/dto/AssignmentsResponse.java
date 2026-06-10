package com.ssuai.domain.lms.dto;

import java.util.List;

/**
 * Pending assignments and quizzes for the current LMS term.
 *
 * @param termId  the canvas term id used to query this response
 * @param items   flat list of pending todo items (may be empty if nothing is due)
 * @param message user-facing explanation when the list is empty, otherwise null
 */
public record AssignmentsResponse(
        long termId,
        List<AssignmentItem> items,
        String message
) {

    public AssignmentsResponse(long termId, List<AssignmentItem> items) {
        this(termId, items, items == null || items.isEmpty()
                ? "현재 미제출 과제나 퀴즈가 없습니다."
                : null);
    }
}
