package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsCourseMaterials(
    LmsCourse course, List<LmsMaterialGroup> groups, int totalCount, long totalBytes) {}
