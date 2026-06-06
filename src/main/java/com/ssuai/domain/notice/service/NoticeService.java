package com.ssuai.domain.notice.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ssuai.domain.notice.connector.DepartmentNoticeConnector;
import com.ssuai.domain.notice.connector.NoticeConnector;
import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.entity.NoticeIndexEntry;
import com.ssuai.domain.notice.repository.NoticeIndexRepository;

@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    static final int MAX_KEYWORD_LENGTH = 64;
    private static final int INDEX_PAGE_SIZE = 20;

    private final NoticeConnector connector;
    private final DepartmentNoticeConnector departmentConnector;
    private final NoticeListCache cache;
    private final NoticeIndexRepository noticeIndexRepository;

    public NoticeService(
            NoticeConnector connector,
            DepartmentNoticeConnector departmentConnector,
            NoticeListCache cache,
            NoticeIndexRepository noticeIndexRepository) {
        this.connector = connector;
        this.departmentConnector = departmentConnector;
        this.cache = cache;
        this.noticeIndexRepository = noticeIndexRepository;
    }

    public NoticeListResponse getRecentNotices(String category, Integer page) {
        int effectivePage = page == null || page < 1 ? 1 : page;
        String cat = normalizeCategory(category);
        return cache.get(
                NoticeListCache.Key.forList(cat, effectivePage),
                () -> connector.fetchNotices(cat, effectivePage));
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
        String cat = normalizeCategory(category);

        // Use local index when populated for date-ranked results; fall back to
        // live scraping when the index has not been seeded yet (dev/test/cold start).
        if (noticeIndexRepository.count() > 0) {
            return searchFromIndex(trimmed, cat, effectivePage);
        }
        return cache.get(
                NoticeListCache.Key.forSearch(trimmed, cat, effectivePage),
                () -> connector.searchNotices(trimmed, cat, effectivePage));
    }

    private NoticeListResponse searchFromIndex(String keyword, String category, int page) {
        Page<NoticeIndexEntry> results = noticeIndexRepository.search(
                keyword == null ? "" : keyword,
                category == null ? "" : category,
                PageRequest.of(page - 1, INDEX_PAGE_SIZE));
        List<Notice> notices = results.getContent().stream()
                .map(NoticeService::toNotice)
                .toList();
        int totalPages = Math.max(1, results.getTotalPages());
        log.debug("notice index search: keyword={} category={} page={} hits={} totalPages={}",
                keyword, category, page, notices.size(), totalPages);
        return new NoticeListResponse(notices, page, totalPages);
    }

    private static Notice toNotice(NoticeIndexEntry entry) {
        String date = entry.getPostedDate() != null ? entry.getPostedDate().toString() : "";
        return new Notice(entry.getTitle(), entry.getLink(), date,
                entry.getStatus(), entry.getDepartment(), entry.getCategory());
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
