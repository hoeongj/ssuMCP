package com.ssuai.domain.notice.connector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;

class MockNoticeConnectorTests {

    private final MockNoticeConnector connector = new MockNoticeConnector();

    @Test
    void fetchNoticesReturnsFixtureNotices() {
        NoticeListResponse response = connector.fetchNotices(null, 1);

        assertThat(response.items()).hasSize(3);
        assertThat(response.currentPage()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    void fetchNoticesFiltersByCategory() {
        NoticeListResponse response = connector.fetchNotices("장학", 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().category()).isEqualTo("장학");
    }

    @Test
    void fetchNoticesWithUnknownCategoryReturnsEmpty() {
        NoticeListResponse response = connector.fetchNotices("존재하지않는카테고리", 1);

        assertThat(response.items()).isEmpty();
    }

    @Test
    void searchNoticesFiltersByKeyword() {
        NoticeListResponse response = connector.searchNotices("장학금", null, 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().title()).contains("장학금");
    }

    @Test
    void searchNoticesFiltersByKeywordAndCategory() {
        NoticeListResponse response = connector.searchNotices("장학금", "장학", 1);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void searchNoticesNoMatch() {
        NoticeListResponse response = connector.searchNotices("없는키워드xyz", null, 1);

        assertThat(response.items()).isEmpty();
    }

    @Test
    void fetchCategoriesReturnsKnownList() {
        NoticeCategoriesResponse response = connector.fetchCategories();

        assertThat(response.categories()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(response.categories())
                .extracting("slug")
                .contains("학사", "장학", "국제교류");
    }

    @Test
    void fetchDetailReturnsBodyText() {
        NoticeDetailResponse response = connector.fetchDetail(
                "https://scatch.ssu.ac.kr/공지사항/장학금-신청-안내/");

        assertThat(response.title()).isEqualTo("2026학년도 1학기 장학금 신청 안내");
        assertThat(response.bodyText()).isNotBlank();
    }

    @Test
    void fetchDetailWithUnknownUrlFallsBackToFirst() {
        NoticeDetailResponse response = connector.fetchDetail("https://example.com/unknown");

        assertThat(response.title()).isNotBlank();
        assertThat(response.bodyText()).isNotBlank();
    }
}
