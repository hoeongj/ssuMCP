package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsExportPrepareResponse(
    int courseCount, int fileCount, long totalBytes,
    List<LmsCourseMaterials> selected, List<LmsExportExclusion> excluded,
    String message) {}
