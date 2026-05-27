package com.ssuai.domain.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.campus.dto.CampusFacilityListResponse;
import com.ssuai.domain.campus.dto.CampusFacilitySearchConstraints;
import com.ssuai.domain.campus.service.CampusFacilityService;

@Component
public class CampusMcpTools {

    private final CampusFacilityService campusFacilityService;

    public CampusMcpTools(CampusFacilityService campusFacilityService) {
        this.campusFacilityService = campusFacilityService;
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
}
