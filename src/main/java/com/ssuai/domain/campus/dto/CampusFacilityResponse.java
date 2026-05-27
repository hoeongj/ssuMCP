package com.ssuai.domain.campus.dto;

import java.util.List;

public record CampusFacilityResponse(
        String id,
        String name,
        CampusFacilityCategory category,
        String categoryLabel,
        String location,
        String phone,
        String extension,
        String fax,
        List<String> weekdayHours,
        List<String> weekendHours,
        List<String> notes,
        List<String> aliases
) {
}
