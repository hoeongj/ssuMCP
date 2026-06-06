package com.ssuai.domain.notice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ssuai.domain.notice.entity.NoticeIndexEntry;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NoticeIndexRepositoryTests {

    @Autowired
    private NoticeIndexRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                entry("장학", "2026학년도 1학기 장학금 신청 안내",
                        "https://scatch.ssu.ac.kr/notice/1",
                        LocalDate.of(2026, 5, 20), "장학팀", "진행"),
                entry("학사", "2026학년도 하계 계절학기 수강신청 안내",
                        "https://scatch.ssu.ac.kr/notice/2",
                        LocalDate.of(2026, 5, 18), "교무팀", "진행"),
                entry("기타", "중앙도서관 좌석 이용 안내 변경",
                        "https://scatch.ssu.ac.kr/notice/3",
                        LocalDate.of(2026, 5, 15), "중앙도서관", "완료"),
                entry("장학", "2025학년도 2학기 장학금 지급 결과",
                        "https://scatch.ssu.ac.kr/notice/4",
                        LocalDate.of(2025, 12, 10), "장학팀", "완료")
        ));
    }

    @Test
    void searchByKeywordReturnsMatches() {
        Page<NoticeIndexEntry> result = repository.search("장학금", "", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .extracting(NoticeIndexEntry::getTitle)
                .allMatch(title -> title.contains("장학금"));
    }

    @Test
    void searchResultsAreOrderedByDateDescending() {
        Page<NoticeIndexEntry> result = repository.search("장학금", "", PageRequest.of(0, 20));

        List<LocalDate> dates = result.getContent().stream()
                .map(NoticeIndexEntry::getPostedDate)
                .toList();
        assertThat(dates).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void searchWithCategoryFilterNarrowsResults() {
        Page<NoticeIndexEntry> result = repository.search("", "장학", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent())
                .extracting(NoticeIndexEntry::getCategory)
                .containsOnly("장학");
    }

    @Test
    void searchWithKeywordAndCategoryFiltersIntersect() {
        Page<NoticeIndexEntry> result = repository.search("장학금", "장학", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void searchWithNonMatchingKeywordReturnsEmpty() {
        Page<NoticeIndexEntry> result = repository.search("없는키워드xyz", "", PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void findByLinkReturnsExistingEntry() {
        assertThat(repository.findByLink("https://scatch.ssu.ac.kr/notice/1")).isPresent();
        assertThat(repository.findByLink("https://does.not.exist/")).isEmpty();
    }

    @Test
    void countReflectsSeededEntries() {
        assertThat(repository.count()).isEqualTo(4);
    }

    private static NoticeIndexEntry entry(
            String category, String title, String link,
            LocalDate postedDate, String department, String status) {
        return new NoticeIndexEntry(category, title, link, postedDate, department, status, Instant.now());
    }
}
