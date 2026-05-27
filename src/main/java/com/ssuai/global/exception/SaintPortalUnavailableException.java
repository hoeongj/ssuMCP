package com.ssuai.global.exception;

/**
 * Thrown when the saint.ssu.ac.kr phase 2 portal fetch fails or returns HTML
 * that {@link com.ssuai.domain.auth.saint.SaintSsoService}'s parser cannot
 * map onto a student identity — upstream 5xx, missing
 * {@code <span class="top_user">} greeting, greeting whose text does not
 * end with a known "님" suffix, or any other shape change in the SAP
 * NetWeaver portal wrapper. Like {@link SaintAuthFailedException}, the SSO
 * callback controller maps this to a 302 redirect with
 * {@code error=portal_unavailable} instead of a JSON error body.
 */
public class SaintPortalUnavailableException extends RuntimeException {

    public SaintPortalUnavailableException(String message) {
        super(message);
    }

    public SaintPortalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
