package com.ssuai.domain.saint.dto;

public record ScholarshipEntry(
        int year,
        String semester,
        String name,
        long receivedAmount,
        String receiveType,
        String status
) {
}
