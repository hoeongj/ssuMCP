package com.ssuai.domain.mcp.tool;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.domain.campus.dto.CampusFacilityListResponse;
import com.ssuai.domain.campus.dto.CampusFacilitySearchConstraints;
import com.ssuai.domain.campus.service.AcademicCalendarService;
import com.ssuai.domain.campus.service.CampusFacilityService;
import com.ssuai.global.exception.ConnectorException;

@Component
public class CampusMcpTools {

    private final CampusFacilityService campusFacilityService;
    private final AcademicCalendarService academicCalendarService;

    public CampusMcpTools(
            CampusFacilityService campusFacilityService,
            AcademicCalendarService academicCalendarService) {
        this.campusFacilityService = campusFacilityService;
        this.academicCalendarService = academicCalendarService;
    }

    @Tool(
            name = "search_campus_facilities",
            description = "숭실대학교 캠퍼스 내 식당, 카페, 편의점, 서점 등의 시설 정보를 검색합니다. 시설 이름, 위치, 운영시간, 전화번호, 별칭 등을 부분 일치로 검색하며, query 가 비어 있으면 전체 시설 목록을 반환합니다."
    )
    public CampusFacilityListResponse searchCampusFacilities(
            @ToolParam(description = "검색어 (선택). 시설명, 별칭, 위치 등에 부분 일치. 비워두면 전체 목록. 최대 64자.", required = false)
            String query
    ) {
        String safeQuery = query == null ? "" : query;
        if (safeQuery.length() > CampusFacilitySearchConstraints.MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "query: 최대 " + CampusFacilitySearchConstraints.MAX_QUERY_LENGTH
                            + "자까지 허용됩니다. 받은 길이: " + safeQuery.length() + "자.");
        }
        return campusFacilityService.searchFacilities(safeQuery);
    }

    @Tool(
            name = "get_academic_calendar",
            description = "숭실대학교 학사일정을 조회합니다. 수강신청·중간/기말고사·방학 등의 주요 일정을 [{date, endDate, event, category}] 형태로 반환합니다. date는 시작일(ISO), endDate는 기간 일정의 종료일(ISO, 포함)이며 하루짜리 일정은 endDate가 null입니다. year를 생략하면 현재 연도를 사용합니다."
    )
    public List<AcademicCalendarEvent> getAcademicCalendar(
            @ToolParam(required = false, description = "조회할 연도(예: 2026). 생략 시 현재 연도.")
            Integer year
    ) {
        try {
            return academicCalendarService.getCalendar(year);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(ConnectorErrorMessages.forResource("학사일정", exception), exception);
        }
    }

    @Tool(
            name = "find_academic_calendar_events",
            description = "숭실대학교 학사일정을 연도, 월, 키워드로 필터링해 반환합니다. 수강신청·개강·종강·시험 같은 일정 검색에 사용합니다."
    )
    public List<AcademicCalendarEvent> findAcademicCalendarEvents(
            @ToolParam(required = false, description = "조회할 연도(예: 2026). 생략 시 현재 연도.")
            Integer year,
            @ToolParam(required = false, description = "조회할 월(1-12). 생략 시 전체 월.")
            Integer month,
            @ToolParam(required = false, description = "일정명 또는 카테고리 키워드. 예: 수강신청, 기말고사.")
            String keyword,
            @ToolParam(required = false, description = "반환할 최대 일정 수. 기본 20, 최대 50.")
            Integer limit
    ) {
        if (month != null && (month < 1 || month > 12)) {
            throw new IllegalArgumentException("month must be between 1 and 12");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT).trim();
        int safeLimit = limit == null || limit < 1 ? 20 : Math.min(limit, 50);
        return getAcademicCalendar(year).stream()
                .filter(event -> month == null || event.date().contains(String.format(Locale.ROOT, "-%02d-", month)))
                .filter(event -> normalizedKeyword.isBlank()
                        || (event.event() != null && event.event().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                        || (event.category() != null && event.category().toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
                .limit(safeLimit)
                .toList();
    }
}
