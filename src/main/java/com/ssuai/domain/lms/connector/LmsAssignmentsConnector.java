package com.ssuai.domain.lms.connector;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.AssignmentsResponse;

public interface LmsAssignmentsConnector {

    /**
     * Fetch pending assignments and quizzes for the student's current LMS term.
     *
     * @param studentId the student's ssuAI/canvas user login (학번)
     * @param cookies   canvas session cookies captured during LMS SSO
     */
    AssignmentsResponse fetchAssignments(String studentId, LmsCookies cookies);
}
