package com.ssuai.domain.campus.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ssuai.domain.campus.connector.AcademicCalendarConnector;
import com.ssuai.domain.campus.dto.AcademicCalendarEvent;

@Service
public class AcademicCalendarService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final AcademicCalendarConnector connector;

    public AcademicCalendarService(AcademicCalendarConnector connector) {
        this.connector = connector;
    }

    public List<AcademicCalendarEvent> getCalendar(Integer year) {
        int resolvedYear = year != null ? year : LocalDate.now(SEOUL_ZONE).getYear();
        return connector.fetchCalendar(resolvedYear);
    }
}
