package com.ssuai.domain.lms.dto;

import java.util.List;

public record LmsMaterialGroup(String extension, int count, List<LmsMaterial> materials) {}
