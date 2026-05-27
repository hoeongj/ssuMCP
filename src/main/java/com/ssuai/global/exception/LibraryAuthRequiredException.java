package com.ssuai.global.exception;

/**
 * Thrown when a library data path requires an upstream session that the
 * current ssuAI session does not have. Mapped by GlobalExceptionHandler
 * to HTTP 401 with code LIBRARY_SESSION_REQUIRED, prompting the frontend
 * to launch the library login capture flow.
 */
public class LibraryAuthRequiredException extends ApiException {

    public LibraryAuthRequiredException() {
        super(ErrorCode.LIBRARY_SESSION_REQUIRED);
    }
}
