package com.ssuai.domain.lms.dto;

import java.util.List;
import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.domain.notice.dto.Notice;

public record LmsDashboardResponse(
    List<AssignmentItem> upcomingDeadlines,   // from LmsAssignmentsService, sorted by dueDate asc
    List<AcademicCalendarEvent> upcomingCalendarEvents,       // from AcademicCalendarService
    List<Notice> activeNotices,                // from NoticeService
    String termName,                           // current term name (from LmsTermItem where defaultTerm=true)
    String message                             // friendly summary or empty string
) {}
