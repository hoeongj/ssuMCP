package com.ssuai.domain.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ssuai.domain.notice.connector.MockDepartmentNoticeConnector;
import com.ssuai.domain.notice.connector.MockNoticeConnector;
import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.repository.NoticeIndexRepository;

@ExtendWith(MockitoExtension.class)
class NoticeServiceTests {

    @Mock
    private NoticeIndexRepository noticeIndexRepository;

    private NoticeService service;

    @BeforeEach
    void setUp() {
        lenient().when(noticeIndexRepository.count()).thenReturn(0L);
        service = new NoticeService(
                new MockNoticeConnector(),
                new MockDepartmentNoticeConnector(),
                new NoticeListCache(java.time.Duration.ofMinutes(5), java.time.Clock.systemUTC(), 500),
                noticeIndexRepository);
    }

    @Test
    void getRecentNoticesReturnsAll() {
        NoticeListResponse response = service.getRecentNotices(null, null);

        assertThat(response.items()).hasSize(3);
        assertThat(response.currentPage()).isEqualTo(1);
    }

    @Test
    void getRecentNoticesFiltersByCategory() {
        NoticeListResponse response = service.getRecentNotices("장학", 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().category()).isEqualTo("장학");
    }

    @Test
    void getRecentNoticesDefaultsPageToOne() {
        NoticeListResponse response = service.getRecentNotices(null, null);

        assertThat(response.currentPage()).isEqualTo(1);
    }

    @Test
    void getRecentNoticesWithNegativePageDefaultsToOne() {
        NoticeListResponse response = service.getRecentNotices(null, -1);

        assertThat(response.currentPage()).isEqualTo(1);
    }

    @Test
    void searchNoticesReturnsMatchingResults() {
        NoticeListResponse response = service.searchNotices("장학금", null, 1);

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void searchNoticesWithEmptyKeywordThrows() {
        assertThatThrownBy(() -> service.searchNotices("", null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검색어를 입력");
    }

    @Test
    void searchNoticesWithNullKeywordThrows() {
        assertThatThrownBy(() -> service.searchNotices(null, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검색어를 입력");
    }

    @Test
    void searchNoticesWithTooLongKeywordThrows() {
        String longKeyword = "가".repeat(65);
        assertThatThrownBy(() -> service.searchNotices(longKeyword, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64자");
    }

    @Test
    void getCategoriesReturnsNonEmpty() {
        NoticeCategoriesResponse response = service.getCategories();

        assertThat(response.categories()).isNotEmpty();
    }

    @Test
    void getNoticeDetailReturnsBodyText() {
        NoticeDetailResponse response = service.getNoticeDetail(
                "https://scatch.ssu.ac.kr/공지사항/장학금-신청-안내/");

        assertThat(response.bodyText()).isNotBlank();
    }

    @Test
    void getNoticeDetailWithNullUrlThrows() {
        assertThatThrownBy(() -> service.getNoticeDetail(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void getNoticeDetailWithBlankUrlThrows() {
        assertThatThrownBy(() -> service.getNoticeDetail("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void getActiveNoticesFiltersOnlyRunning() {
        NoticeListResponse response = service.getActiveNotices(null, 1);

        assertThat(response.items())
                .extracting("status")
                .containsOnly("진행");
        // Mock has 2 active notices
        assertThat(response.items()).hasSize(2);
    }

    @Test
    void getActiveNoticesWithCategoryFilter() {
        NoticeListResponse response = service.getActiveNotices("장학", 1);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().status()).isEqualTo("진행");
        assertThat(response.items().getFirst().category()).isEqualTo("장학");
    }

    @Test
    void getDepartmentNoticesFiltersByDepartment() {
        NoticeListResponse response = service.getDepartmentNotices("장학팀", 1);

        assertThat(response.items())
                .extracting("department")
                .allSatisfy(dept -> assertThat((String) dept).contains("장학팀"));
    }

    @Test
    void getDepartmentNoticesWithNullThrows() {
        assertThatThrownBy(() -> service.getDepartmentNotices(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("학과/부서");
    }

    @Test
    void getDepartmentNoticesWithBlankThrows() {
        assertThatThrownBy(() -> service.getDepartmentNotices("  ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("학과/부서");
    }
}
