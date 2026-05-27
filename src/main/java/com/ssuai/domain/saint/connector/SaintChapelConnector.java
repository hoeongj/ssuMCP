package com.ssuai.domain.saint.connector;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.saint.dto.ChapelInfo;

/**
 * Fetches the student's u-SAINT chapel attendance information using stored
 * SAINT session material.
 */
public interface SaintChapelConnector {

    ChapelInfo fetchChapelInfo(String studentId, PortalCookies cookies, Integer year, String semester);
}
