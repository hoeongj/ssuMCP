package com.ssuai.domain.saint.dto;

public record ChapelAbsenceApplication(
        String category,
        String startDate,
        String endDate,
        String reason,
        String status
) {
}
