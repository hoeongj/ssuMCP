package com.ssuai.domain.notice.dto;

import java.util.List;

public record NoticeListResponse(
        List<Notice> items,
        int currentPage,
        int totalPages
) {}
