package com.ssuai.domain.notice.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ssuai.domain.notice.connector.DepartmentNoticeConnector;
import com.ssuai.domain.notice.connector.NoticeConnector;
import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.dto.Notice;

@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    static final int MAX_KEYWORD_LENGTH = 64;

    private final NoticeConnector connector;
    private final DepartmentNoticeConnector departmentConnector;

    public NoticeService(NoticeConnector connector, DepartmentNoticeConnector departmentConnector) {
        this.connector = connector;
        this.departmentConnector = departmentConnector;
    }

    public NoticeListResponse getRecentNotices(String category, Integer page) {
        int effectivePage = page == null || page < 1 ? 1 : page;
        return connector.fetchNotices(normalizeCategory(category), effectivePage);
    }

    public NoticeListResponse searchNotices(String keyword, String category, Integer page) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("검색어를 입력해 주세요.");
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "검색어는 " + MAX_KEYWORD_LENGTH + "자 이하로 입력해 주세요.");
        }
        int effectivePage = page == null || page < 1 ? 1 : page;
        return connector.searchNotices(trimmed, normalizeCategory(category), effectivePage);
    }

    public NoticeCategoriesResponse getCategories() {
        return connector.fetchCategories();
    }

    public NoticeDetailResponse getNoticeDetail(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("공지 URL을 입력해 주세요.");
        }
        return connector.fetchDetail(url.trim());
    }

    public NoticeListResponse getActiveNotices(String category, Integer page) {
        NoticeListResponse all = getRecentNotices(category, page);
        List<Notice> active = all.items().stream()
                .filter(notice -> "진행".equals(notice.status()))
                .toList();
        return new NoticeListResponse(active, all.currentPage(), all.totalPages());
    }

    public NoticeListResponse getDepartmentNotices(String department, Integer page) {
        if (department == null || department.isBlank()) {
            throw new IllegalArgumentException("학과/부서 이름을 입력해 주세요.");
        }
        int effectivePage = page == null || page < 1 ? 1 : page;
        return departmentConnector.fetchByDepartment(department.trim(), effectivePage);
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim();
    }
}
