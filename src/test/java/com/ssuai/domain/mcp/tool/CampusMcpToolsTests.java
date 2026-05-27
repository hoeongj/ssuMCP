package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.campus.dto.CampusFacilityListResponse;
import com.ssuai.domain.campus.service.CampusFacilityService;

class CampusMcpToolsTests {

    private final CampusFacilityService campusFacilityService = mock(CampusFacilityService.class);
    private final CampusMcpTools tools = new CampusMcpTools(campusFacilityService);

    @Test
    void searchCampusFacilitiesDelegatesBlankQueryForNull() {
        CampusFacilityListResponse expected = new CampusFacilityListResponse(List.of());
        when(campusFacilityService.searchFacilities("")).thenReturn(expected);

        CampusFacilityListResponse response = tools.searchCampusFacilities(null);

        assertThat(response).isSameAs(expected);
        verify(campusFacilityService).searchFacilities("");
    }

    @Test
    void searchCampusFacilitiesDelegatesQuery() {
        CampusFacilityListResponse expected = new CampusFacilityListResponse(List.of());
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
        CampusFacilityListResponse expected = new CampusFacilityListResponse(List.of());
        when(campusFacilityService.searchFacilities(query)).thenReturn(expected);

        CampusFacilityListResponse response = tools.searchCampusFacilities(query);

        assertThat(response).isSameAs(expected);
        verify(campusFacilityService).searchFacilities(query);
    }
}
