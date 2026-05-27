package com.ssuai.domain.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.service.NoticeService;
import com.ssuai.global.exception.ConnectorException;

@Component
public class NoticeMcpTools {

    private final NoticeService noticeService;

    public NoticeMcpTools(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @Tool(
            name = "get_recent_notices",
            description = "숭실대 학교 공지사항 최신 목록을 조회합니다. "
                    + "category 를 지정하면 해당 카테고리만 필터링합니다. "
                    + "카테고리 목록은 list_notice_categories 도구로 확인할 수 있습니다."
    )
    public NoticeListResponse getRecentNotices(
            @ToolParam(description = "카테고리 (선택): 학사/장학/국제교류/외국인유학생/채용/비교과·행사/교원채용/교직/봉사/기타. 비워두면 전체.", required = false)
            String category,
            @ToolParam(description = "페이지 번호 (1부터). 기본값 1.", required = false)
            Integer page
    ) {
        try {
            return noticeService.getRecentNotices(category, page);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("공지사항", exception), exception);
        }
    }

    @Tool(
            name = "search_notices",
            description = "숭실대 공지사항을 키워드로 검색합니다. "
                    + "제목·본문에 키워드가 포함된 공지를 반환합니다. "
                    + "category 를 함께 지정하면 해당 카테고리 내에서만 검색합니다."
    )
    public NoticeListResponse searchNotices(
            @ToolParam(description = "검색 키워드. 최대 64자.")
            String keyword,
            @ToolParam(description = "카테고리 필터 (선택).", required = false)
            String category,
            @ToolParam(description = "페이지 번호 (기본 1).", required = false)
            Integer page
    ) {
        try {
            return noticeService.searchNotices(keyword, category, page);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("공지사항 검색", exception), exception);
        }
    }

    @Tool(
            name = "list_notice_categories",
            description = "숭실대 공지사항 카테고리 목록을 반환합니다. "
                    + "get_recent_notices, search_notices 의 category 파라미터에 사용할 수 있는 값을 확인할 때 유용합니다."
    )
    public NoticeCategoriesResponse listNoticeCategories() {
        try {
            return noticeService.getCategories();
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("공지 카테고리", exception), exception);
        }
    }

    @Tool(
            name = "get_notice_detail",
            description = "공지 URL 로 본문 전체 텍스트를 반환합니다. "
                    + "get_recent_notices 나 search_notices 결과의 link 값을 그대로 전달하세요."
    )
    public NoticeDetailResponse getNoticeDetail(
            @ToolParam(description = "공지 URL. get_recent_notices/search_notices 결과의 link 값.")
            String url
    ) {
        try {
            return noticeService.getNoticeDetail(url);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("공지 상세", exception), exception);
        }
    }

    @Tool(
            name = "get_active_notices",
            description = "진행중(마감 전) 공지만 반환합니다. "
                    + "마감 임박 공지나 현재 신청 가능한 공지를 확인할 때 유용합니다."
    )
    public NoticeListResponse getActiveNotices(
            @ToolParam(description = "카테고리 필터 (선택).", required = false)
            String category
    ) {
        try {
            return noticeService.getActiveNotices(category, 1);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("진행중 공지", exception), exception);
        }
    }

    @Tool(
            name = "get_department_notices",
            description = "특정 학과/부서 공지만 반환합니다. "
                    + "예: '컴퓨터학부', '장학팀', '국제팀', '소프트웨어학부'."
    )
    public NoticeListResponse getDepartmentNotices(
            @ToolParam(description = "학과/부서 이름. 예: 컴퓨터학부, 장학팀, 소프트웨어학부.")
            String department,
            @ToolParam(description = "페이지 번호 (기본 1).", required = false)
            Integer page
    ) {
        try {
            return noticeService.getDepartmentNotices(department, page);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("학과 공지", exception), exception);
        }
    }
}
