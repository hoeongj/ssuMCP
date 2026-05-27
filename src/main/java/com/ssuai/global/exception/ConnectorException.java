package com.ssuai.global.exception;

import java.util.Objects;

public class ConnectorException extends RuntimeException {

    private final ErrorCode errorCode;

    public ConnectorException() {
        this(ErrorCode.CONNECTOR_ERROR, null);
    }

    public ConnectorException(Throwable cause) {
        this(ErrorCode.CONNECTOR_ERROR, cause);
    }

    protected ConnectorException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    protected ConnectorException(ErrorCode errorCode, Throwable cause) {
        super(Objects.requireNonNull(errorCode).getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
