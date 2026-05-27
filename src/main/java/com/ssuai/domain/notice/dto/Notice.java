package com.ssuai.domain.notice.dto;

public record Notice(
        String title,
        String link,
        String date,
        String status,
        String department,
        String category
) {}
