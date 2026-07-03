package com.ssuai.domain.campus.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;

@Component
@ConditionalOnProperty(name = "ssuai.connector.academic-calendar", havingValue = "mock", matchIfMissing = true)
class MockAcademicCalendarConnector implements AcademicCalendarConnector {

    @Override
    public List<AcademicCalendarEvent> fetchCalendar(int year) {
        if (year == 2026) {
            // Multi-day entries carry an inclusive endDate (like the real page's
            // "MM.DD ~ MM.DD" rows); single-day entries leave it null.
            return List.of(
                    new AcademicCalendarEvent("2026-01-05", null, "겨울방학 종료", "방학"),
                    new AcademicCalendarEvent("2026-02-16", "2026-02-20", "봄학기 수강신청", "수강신청"),
                    new AcademicCalendarEvent("2026-03-02", null, "봄학기 개강", "학사"),
                    new AcademicCalendarEvent("2026-04-20", "2026-04-24", "봄학기 중간고사", "시험"),
                    new AcademicCalendarEvent("2026-06-15", "2026-06-19", "봄학기 기말고사", "시험"),
                    new AcademicCalendarEvent("2026-06-26", null, "봄학기 종강", "학사"),
                    new AcademicCalendarEvent("2026-06-27", null, "여름방학 시작", "방학"),
                    new AcademicCalendarEvent("2026-08-17", "2026-08-21", "가을학기 수강신청", "수강신청"),
                    new AcademicCalendarEvent("2026-09-01", null, "가을학기 개강", "학사"),
                    new AcademicCalendarEvent("2026-10-19", "2026-10-23", "가을학기 중간고사", "시험"),
                    new AcademicCalendarEvent("2026-12-14", "2026-12-18", "가을학기 기말고사", "시험"),
                    new AcademicCalendarEvent("2026-12-25", null, "가을학기 종강", "학사")
            );
        }
        return List.of();
    }
}
