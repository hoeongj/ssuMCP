package com.ssuai.domain.library.dto;

public enum BookStatus {
    AVAILABLE,
    CHECKED_OUT,
    UNKNOWN;

    public static BookStatus fromPyxisCode(String pyxisCode) {
        if (pyxisCode == null) {
            return UNKNOWN;
        }
        return switch (pyxisCode.trim().toUpperCase()) {
            case "READY" -> AVAILABLE;
            case "LOAN", "CHECKED_OUT" -> CHECKED_OUT;
            default -> UNKNOWN;
        };
    }
}
