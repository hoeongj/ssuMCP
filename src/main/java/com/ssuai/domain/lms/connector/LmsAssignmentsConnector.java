package com.ssuai.domain.lms.connector;

import java.util.List;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.AssignmentsResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;

public interface LmsAssignmentsConnector {

    /**
     * Returns all enrollment terms available for the student.
     */
    List<LmsTermItem> fetchTerms(String studentId, LmsCookies cookies);

    /**
     * Fetch pending assignments and quizzes for the given term.
     * If termId is null the connector auto-selects the Canvas default term.
     *
     * @param studentId the student's ssuAI/canvas user login (학번)
     * @param cookies   canvas session cookies captured during LMS SSO
     * @param termId    Canvas term ID from fetchTerms; null = auto-detect
     */
    AssignmentsResponse fetchAssignments(String studentId, LmsCookies cookies, Long termId);
}
