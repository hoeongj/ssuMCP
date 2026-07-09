package com.ssuai.domain.saint.dto;

import java.util.List;

public record ChapelInfo(
        int year,
        String semester,
        String chapelTime,
        String chapelRoom,
        String seatNumber,
        Integer absenceAllowedCount,
        int absenceUsedCount,
        String result,
        List<ChapelAttendanceEntry> attendances,
        List<ChapelAbsenceApplication> absenceApplications
) {

    public ChapelInfo {
        attendances = attendances == null ? List.of() : List.copyOf(attendances);
        absenceApplications = absenceApplications == null ? List.of() : List.copyOf(absenceApplications);
    }
}
