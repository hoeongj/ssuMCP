package com.ssuai.global.auth;

/**
 * Names of the {@code ServletRequest} attributes {@link JwtAuthFilter}
 * populates after a successful access-token parse. Controllers that need
 * the current ssuAI user read these directly via
 * {@link jakarta.servlet.ServletRequest#getAttribute(String)} — no Spring
 * Security {@code Authentication} object is involved (Task 14 spec §6).
 *
 * <p>If neither attribute is present on a request, the caller is anonymous
 * (no access JWT, malformed header, or invalid token — the filter is
 * deliberately quiet and lets controllers decide whether to 401).
 */
public final class AuthAttributes {

    public static final String STUDENT_ID = "ssuai.studentId";
    public static final String STUDENT_NAME = "ssuai.studentName";

    private AuthAttributes() {
    }
}
