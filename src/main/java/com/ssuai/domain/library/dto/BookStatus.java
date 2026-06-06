package com.ssuai.domain.library.dto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum BookStatus {
    AVAILABLE,
    CHECKED_OUT,
    UNKNOWN;

    private static final Logger log = LoggerFactory.getLogger(BookStatus.class);

    public static BookStatus fromPyxisCode(String pyxisCode) {
        if (pyxisCode == null) {
            log.warn("unknown Pyxis cStateCode: {}", (Object) null);
            return UNKNOWN;
        }
        return switch (pyxisCode.trim().toUpperCase()) {
            case "READY" -> AVAILABLE;
            case "LOAN", "CHECKED_OUT" -> CHECKED_OUT;
            default -> {
                log.warn("unknown Pyxis cStateCode: {}", pyxisCode);
                yield UNKNOWN;
            }
        };
    }
}
