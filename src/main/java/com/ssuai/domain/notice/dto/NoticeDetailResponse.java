package com.ssuai.domain.notice.dto;

public record NoticeDetailResponse(
        String title,
        String link,
        String date,
        String status,
        String department,
        String category,
        String bodyText
) {}
