package com.ssuai.domain.lms.service;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.domain.campus.service.AcademicCalendarService;
import com.ssuai.domain.lms.dto.AssignmentItem;
import com.ssuai.domain.lms.dto.LmsDashboardResponse;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.service.NoticeService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LmsDashboardService {

    private final LmsAssignmentsService assignmentsService;
    private final AcademicCalendarService academicCalendarService;
    private final NoticeService noticeService;

    public LmsDashboardService(
            LmsAssignmentsService assignmentsService,
            AcademicCalendarService academicCalendarService,
            NoticeService noticeService) {
        this.assignmentsService = assignmentsService;
        this.academicCalendarService = academicCalendarService;
        this.noticeService = noticeService;
    }

    /**
     * Aggregates LMS assignment deadlines, academic calendar events,
     * and active school notices into one dashboard response.
     *
     * @param studentId   authenticated student ID (from LMS session)
     * @param termId      optional term filter; null = current default term
     */
    public LmsDashboardResponse getDashboard(String studentId, Long termId) {
        // 1. Determine current term name
        List<LmsTermItem> terms = assignmentsService.fetchTerms(studentId);
        long resolvedTermId = (termId != null) ? termId : LmsTermResolver.resolveCurrentTermId(terms);
        LmsTermItem currentTerm = terms.stream()
                .filter(t -> t.id() == resolvedTermId)
                .findFirst()
                .orElse(terms.isEmpty() ? null : terms.get(0));
        String termName = currentTerm != null ? currentTerm.name() : "";

        // 2. Upcoming assignment/quiz deadlines
        var assignments = assignmentsService.fetchAssignments(studentId, resolvedTermId);
        List<AssignmentItem> sortedDeadlines = (assignments == null || assignments.items() == null)
                ? List.of()
                : assignments.items().stream()
                        .filter(item -> item.dueDate() != null)
                        .sorted(Comparator.comparing(AssignmentItem::dueDate))
                        .toList();

        // 3. Upcoming academic calendar events (next 60 days)
        List<AcademicCalendarEvent> calendarEvents = fetchUpcomingCalendarEvents();

        // 4. Active notices
        var noticeResponse = noticeService.getActiveNotices(null, null);
        List<Notice> notices = noticeResponse != null && noticeResponse.items() != null
                ? noticeResponse.items()
                : List.of();

        // 5. Build message
        int deadlineCount = sortedDeadlines.size();
        String message = deadlineCount == 0
                ? "현재 제출 기한이 다가온 과제·퀴즈가 없습니다."
                : String.format("제출 기한이 다가온 과제·퀴즈가 %d개 있습니다.", deadlineCount);

        return new LmsDashboardResponse(sortedDeadlines, calendarEvents, notices, termName, message);
    }

    // Helper — fetches academic calendar events for the next 60 days
    private List<AcademicCalendarEvent> fetchUpcomingCalendarEvents() {
        ZoneId seoulZone = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(seoulZone);
        LocalDate sixtyDaysLater = today.plusDays(60);

        List<AcademicCalendarEvent> events = new ArrayList<>();
        try {
            List<AcademicCalendarEvent> currentYearEvents = academicCalendarService.getCalendar(today.getYear());
            if (currentYearEvents != null) {
                events.addAll(currentYearEvents);
            }
        } catch (Exception e) {
            // Ignore connector failure for resilience
        }

        if (sixtyDaysLater.getYear() > today.getYear()) {
            try {
                List<AcademicCalendarEvent> nextYearEvents = academicCalendarService.getCalendar(sixtyDaysLater.getYear());
                if (nextYearEvents != null) {
                    events.addAll(nextYearEvents);
                }
            } catch (Exception e) {
                // Ignore connector failure for resilience
            }
        }

        return events.stream()
                .filter(event -> {
                    if (event.date() == null || event.date().isBlank()) {
                        return false;
                    }
                    try {
                        LocalDate eventDate = LocalDate.parse(event.date());
                        return !eventDate.isBefore(today) && !eventDate.isAfter(sixtyDaysLater);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .sorted(Comparator.comparing(AcademicCalendarEvent::date))
                .toList();
    }
}
