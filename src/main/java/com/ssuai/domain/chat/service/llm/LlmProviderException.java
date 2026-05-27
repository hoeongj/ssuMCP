package com.ssuai.domain.chat.service.llm;

public class LlmProviderException extends RuntimeException {

    private final String providerName;
    private final int statusCode;
    private final String responseBody;
    private final boolean fallbackable;

    public LlmProviderException(String providerName, String message, boolean fallbackable) {
        this(providerName, message, 0, "", fallbackable, null);
    }

    public LlmProviderException(
            String providerName,
            String message,
            int statusCode,
            String responseBody,
            boolean fallbackable,
            Throwable cause
    ) {
        super(message, cause);
        this.providerName = providerName;
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.fallbackable = fallbackable;
    }

    public String providerName() {
        return providerName;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public boolean fallbackable() {
        return fallbackable;
    }
}
