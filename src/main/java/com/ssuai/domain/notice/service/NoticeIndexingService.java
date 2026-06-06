package com.ssuai.domain.notice.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.connector.NoticeConnector;
import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.entity.NoticeIndexEntry;
import com.ssuai.domain.notice.repository.NoticeIndexRepository;
import com.ssuai.global.exception.ConnectorException;

/**
 * Background crawler that keeps the local notice index up to date.
 * Runs only when the real connector is active (production environment).
 *
 * <p>Crawls the most recent pages of each notice category and upserts rows
 * into {@code notice_index} keyed by URL. The {@link NoticeIndexRepository}
 * is then used by {@link NoticeService#searchNotices} to return results
 * sorted by posting date rather than the scatch search engine's heuristic.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.notice", havingValue = "real")
public class NoticeIndexingService {

    private static final Logger log = LoggerFactory.getLogger(NoticeIndexingService.class);

    private static final List<String> CATEGORIES = List.of(
            "학사", "장학", "국제교류", "외국인유학생",
            "채용", "비교과·행사", "교원채용", "교직", "봉사", "기타");
    private static final int MAX_PAGES_PER_CATEGORY = 3;
    private static final int PAGE_SIZE = 20;

    private final NoticeConnector connector;
    private final NoticeIndexRepository repository;

    public NoticeIndexingService(NoticeConnector connector, NoticeIndexRepository repository) {
        this.connector = connector;
        this.repository = repository;
    }

    @Scheduled(initialDelay = 10, fixedDelay = 240, timeUnit = TimeUnit.MINUTES)
    public void crawlAndIndex() {
        log.info("notice indexing started");
        int total = 0;
        for (String category : CATEGORIES) {
            try {
                total += crawlCategory(category);
            } catch (Exception exception) {
                log.warn("notice indexing error: category={} message={}", category, exception.getMessage());
            }
        }
        log.info("notice indexing done: upserted={}", total);
    }

    private int crawlCategory(String category) {
        int count = 0;
        for (int page = 1; page <= MAX_PAGES_PER_CATEGORY; page++) {
            NoticeListResponse response;
            try {
                response = connector.fetchNotices(category, page);
            } catch (ConnectorException exception) {
                log.debug("notice connector error: category={} page={}: {}", category, page, exception.getMessage());
                break;
            }
            for (Notice notice : response.items()) {
                if (upsert(notice, category)) {
                    count++;
                }
            }
            if (page >= response.totalPages() || response.items().isEmpty()) {
                break;
            }
        }
        return count;
    }

    private boolean upsert(Notice notice, String crawledCategory) {
        if (notice.link() == null || notice.link().isBlank()) {
            return false;
        }
        String effectiveCategory = !notice.category().isBlank() ? notice.category() : crawledCategory;
        LocalDate postedDate = parseDate(notice.date());
        Instant now = Instant.now();

        NoticeIndexEntry entry = repository.findByLink(notice.link()).orElse(null);
        if (entry == null) {
            entry = new NoticeIndexEntry(effectiveCategory, notice.title(), notice.link(),
                    postedDate, notice.department(), notice.status(), now);
        } else {
            entry.setCategory(effectiveCategory);
            entry.setTitle(notice.title());
            entry.setPostedDate(postedDate);
            entry.setDepartment(notice.department());
            entry.setStatus(notice.status());
            entry.setIndexedAt(now);
        }
        repository.save(entry);
        return true;
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
