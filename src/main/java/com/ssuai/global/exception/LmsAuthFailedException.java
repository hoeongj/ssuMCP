package com.ssuai.global.exception;

public class LmsAuthFailedException extends ApiException {

    public LmsAuthFailedException(String message) {
        super(ErrorCode.LMS_AUTH_FAILED, message);
    }

    public LmsAuthFailedException(String message, Throwable cause) {
        super(ErrorCode.LMS_AUTH_FAILED, message);
        initCause(cause);
    }
}
