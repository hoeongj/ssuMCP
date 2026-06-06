package com.ssuai.global.exception;

public class LibrarySeatNotAvailableException extends RuntimeException {

    private final String pyxisCode;

    public LibrarySeatNotAvailableException(String pyxisCode) {
        super("좌석을 예약할 수 없습니다: " + pyxisCode);
        this.pyxisCode = pyxisCode;
    }

    public String getPyxisCode() {
        return pyxisCode;
    }
}
