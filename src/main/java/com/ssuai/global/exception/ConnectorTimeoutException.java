package com.ssuai.global.exception;

public class ConnectorTimeoutException extends ConnectorException {

    public ConnectorTimeoutException() {
        super(ErrorCode.CONNECTOR_TIMEOUT);
    }

    public ConnectorTimeoutException(Throwable cause) {
        super(ErrorCode.CONNECTOR_TIMEOUT, cause);
    }
}
