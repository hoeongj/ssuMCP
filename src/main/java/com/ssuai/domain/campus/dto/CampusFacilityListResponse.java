package com.ssuai.domain.campus.dto;

import java.util.List;

public record CampusFacilityListResponse(
        List<CampusFacilityResponse> facilities
) {
}
