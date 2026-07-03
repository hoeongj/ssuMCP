package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.campus.dto.AcademicCalendarEvent;
import com.ssuai.domain.campus.dto.CampusFacilityListResponse;
import com.ssuai.domain.campus.service.AcademicCalendarService;
import com.ssuai.domain.campus.service.CampusFacilityService;

class CampusMcpToolsTests {

    private final CampusFacilityService campusFacilityService = mock(CampusFacilityService.class);
    private final AcademicCalendarService academicCalendarService = mock(AcademicCalendarService.class);
    private final CampusMcpTools tools = new CampusMcpTools(campusFacilityService, academicCalendarService);

    @Test
    void searchCampusFacilitiesDelegatesBlankQueryForNull() {
        CampusFacilityListResponse expected = CampusFacilityListResponse.of(List.of());
        when(campusFacilityService.searchFacilities("")).thenReturn(expected);

        CampusFacilityListResponse response = tools.searchCampusFacilities(null);

        assertThat(response).isSameAs(expected);
        assertThat(response.empty()).isTrue();
        assertThat(response.note()).isEqualTo("검색 조건에 맞는 시설이 없어요.");
        verify(campusFacilityService).searchFacilities("");
    }

    @Test
    void searchCampusFacilitiesDelegatesQuery() {
        CampusFacilityListResponse expected = CampusFacilityListResponse.of(List.of());
        when(campusFacilityService.searchFacilities("학식")).thenReturn(expected);

        CampusFacilityListResponse response = tools.searchCampusFacilities("학식");

        assertThat(response).isSameAs(expected);
        verify(campusFacilityService).searchFacilities("학식");
    }

    @Test
    void searchCampusFacilitiesRejectsQueryLongerThanLimit() {
        assertThatThrownBy(() -> tools.searchCampusFacilities("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64")
                .hasMessageContaining("65");
        verifyNoInteractions(campusFacilityService);
    }

    @Test
    void searchCampusFacilitiesAllowsQueryAtLimit() {
        String query = "a".repeat(64);
        CampusFacilityListResponse expected = CampusFacilityListResponse.of(List.of());
        when(campusFacilityService.searchFacilities(query)).thenReturn(expected);

        CampusFacilityListResponse response = tools.searchCampusFacilities(query);

        assertThat(response).isSameAs(expected);
        verify(campusFacilityService).searchFacilities(query);
    }

    @Test
    void findAcademicCalendarEventsFiltersByMonthKeywordAndLimit() {
        when(academicCalendarService.getCalendar(2026)).thenReturn(List.of(
                new AcademicCalendarEvent("2026-02-16", "2026-02-20", "봄학기 수강신청", "수강신청"),
                new AcademicCalendarEvent("2026-02-20", null, "수강신청 변경", "수강신청"),
                new AcademicCalendarEvent("2026-03-02", null, "봄학기 개강", "학사")));

        List<AcademicCalendarEvent> response =
                tools.findAcademicCalendarEvents(2026, 2, "수강신청", 1);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().event()).isEqualTo("봄학기 수강신청");
        verify(academicCalendarService).getCalendar(2026);
    }

    @Test
    void findAcademicCalendarEventsRejectsInvalidMonth() {
        assertThatThrownBy(() -> tools.findAcademicCalendarEvents(2026, 13, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("month");
    }
}
