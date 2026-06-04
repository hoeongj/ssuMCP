package com.ssuai.domain.campus.connector;

import java.util.List;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;

public interface AcademicCalendarConnector {

    List<AcademicCalendarEvent> fetchCalendar(int year);
}
