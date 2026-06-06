package com.ssuai.domain.notice.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeCategory;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;

@Component
@ConditionalOnProperty(name = "ssuai.connector.notice", havingValue = "mock", matchIfMissing = true)
public class MockNoticeConnector implements NoticeConnector {

    private static final List<Notice> FIXTURE_NOTICES = List.of(
            new Notice(
                    "2026학년도 1학기 장학금 신청 안내",
                    "https://scatch.ssu.ac.kr/공지사항/장학금-신청-안내/",
                    "2026-05-20",
                    "진행",
                    "장학팀",
                    "장학"
            ),
            new Notice(
                    "2026학년도 하계 계절학기 수강신청 안내",
                    "https://scatch.ssu.ac.kr/공지사항/계절학기-수강신청/",
                    "2026-05-18",
                    "진행",
                    "교무팀",
                    "학사"
            ),
            new Notice(
                    "중앙도서관 좌석 이용 안내 변경",
                    "https://scatch.ssu.ac.kr/공지사항/도서관-좌석-안내/",
                    "2026-05-15",
                    "완료",
                    "중앙도서관",
                    "기타"
            )
    );

    private static final List<NoticeCategory> FIXTURE_CATEGORIES = List.of(
            new NoticeCategory("학사", "학사"),
            new NoticeCategory("장학", "장학"),
            new NoticeCategory("국제교류", "국제교류"),
            new NoticeCategory("외국인유학생", "외국인유학생"),
            new NoticeCategory("채용", "채용"),
            new NoticeCategory("비교과·행사", "비교과·행사"),
            new NoticeCategory("교원채용", "교원채용"),
            new NoticeCategory("교직", "교직"),
            new NoticeCategory("봉사", "봉사"),
            new NoticeCategory("기타", "기타")
    );

    @Override
    public NoticeListResponse fetchNotices(String category, int page) {
        List<Notice> filtered = filterByCategory(category);
        return new NoticeListResponse(filtered, page, 1);
    }

    @Override
    public NoticeListResponse searchNotices(String keyword, String category, int page) {
        List<Notice> filtered = filterByCategory(category).stream()
                .filter(notice -> notice.title().contains(keyword)
                        || notice.department().contains(keyword))
                .toList();
        return new NoticeListResponse(filtered, page, 1);
    }

    @Override
    public NoticeCategoriesResponse fetchCategories() {
        return new NoticeCategoriesResponse(FIXTURE_CATEGORIES);
    }

    @Override
    public NoticeDetailResponse fetchDetail(String url) {
        Notice match = FIXTURE_NOTICES.stream()
                .filter(notice -> notice.link().equals(url))
                .findFirst()
                .orElse(FIXTURE_NOTICES.getFirst());
        return new NoticeDetailResponse(
                match.title(),
                match.link(),
                match.date(),
                match.status(),
                match.department(),
                match.category(),
                "공지 본문 예시입니다. " + match.title() + "에 대한 상세 내용이 여기에 표시됩니다."
        );
    }

    private List<Notice> filterByCategory(String category) {
        if (category == null || category.isBlank()) {
            return FIXTURE_NOTICES;
        }
        return FIXTURE_NOTICES.stream()
                .filter(notice -> notice.category().equals(category))
                .toList();
    }
}
