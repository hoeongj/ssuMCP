package com.ssuai.domain.saint.connector;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.GraduationStatus;

/**
 * Fetches the student's read-only u-SAINT graduation requirement status.
 */
public interface SaintGraduationConnector {

    GraduationStatus fetchGraduationRequirements(String studentId, PortalCookies cookies);
}
