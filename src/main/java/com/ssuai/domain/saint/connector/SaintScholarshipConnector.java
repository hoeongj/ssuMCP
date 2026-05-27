package com.ssuai.domain.saint.connector;

import java.util.List;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ScholarshipEntry;

/**
 * Fetches the student's read-only u-SAINT scholarship receipt history.
 */
public interface SaintScholarshipConnector {

    List<ScholarshipEntry> fetchScholarships(String studentId, PortalCookies cookies);
}
