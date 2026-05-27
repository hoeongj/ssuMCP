package com.ssuai.domain.library.dto;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum LibraryFloor {

    F2(2, "2층"),
    F5(5, "5층"),
    F6(6, "6층");

    private final int code;
    private final String displayLabel;

    LibraryFloor(int code, String displayLabel) {
        this.code = code;
        this.displayLabel = displayLabel;
    }

    public int code() {
        return code;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public static LibraryFloor fromCode(int code) {
        for (LibraryFloor floor : values()) {
            if (floor.code == code) {
                return floor;
            }
        }
        throw new IllegalArgumentException(
                "floor: 지원하지 않는 도서관 층입니다. 가능한 값: " + allowedCodes()
                        + ". 받은 값: " + code + ".");
    }

    private static String allowedCodes() {
        return Arrays.stream(values())
                .map(floor -> Integer.toString(floor.code))
                .collect(Collectors.joining(", "));
    }
}
