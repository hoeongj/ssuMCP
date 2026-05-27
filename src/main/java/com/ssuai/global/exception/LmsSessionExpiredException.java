package com.ssuai.global.exception;

public class LmsSessionExpiredException extends ApiException {

    public LmsSessionExpiredException() {
        super(ErrorCode.LMS_SESSION_EXPIRED);
    }

    public LmsSessionExpiredException(String message) {
        super(ErrorCode.LMS_SESSION_EXPIRED, message);
    }
}
