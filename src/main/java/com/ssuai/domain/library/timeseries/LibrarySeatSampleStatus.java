package com.ssuai.domain.library.timeseries;

import java.util.Locale;

enum LibrarySeatSampleStatus {
    AVAILABLE("available", "A"),
    OCCUPIED("occupied", "O"),
    AWAY("away", "W"),
    INACTIVE("inactive", "I"),
    UNKNOWN("unknown", "U");

    private final String source;
    private final String code;

    LibrarySeatSampleStatus(String source, String code) {
        this.source = source;
        this.code = code;
    }

    String code() {
        return code;
    }

    static String codeFor(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN.code;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        for (LibrarySeatSampleStatus value : values()) {
            if (value.source.equals(normalized)) {
                return value.code;
            }
        }
        return UNKNOWN.code;
    }
}
