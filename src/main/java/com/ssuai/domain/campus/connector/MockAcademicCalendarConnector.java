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
            return List.of(
                    new AcademicCalendarEvent("2026-01-05", "겨울방학 종료", "방학"),
                    new AcademicCalendarEvent("2026-02-16", "봄학기 수강신청", "수강신청"),
                    new AcademicCalendarEvent("2026-03-02", "봄학기 개강", "학사"),
                    new AcademicCalendarEvent("2026-04-20", "봄학기 중간고사", "시험"),
                    new AcademicCalendarEvent("2026-06-15", "봄학기 기말고사", "시험"),
                    new AcademicCalendarEvent("2026-06-26", "봄학기 종강", "학사"),
                    new AcademicCalendarEvent("2026-06-27", "여름방학 시작", "방학"),
                    new AcademicCalendarEvent("2026-08-17", "가을학기 수강신청", "수강신청"),
                    new AcademicCalendarEvent("2026-09-01", "가을학기 개강", "학사"),
                    new AcademicCalendarEvent("2026-10-19", "가을학기 중간고사", "시험"),
                    new AcademicCalendarEvent("2026-12-14", "가을학기 기말고사", "시험"),
                    new AcademicCalendarEvent("2026-12-25", "가을학기 종강", "학사")
            );
        }
        return List.of();
    }
}
