package com.ssuai.domain.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.domain.notice.service.NoticeService;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/notices")
@Tag(name = "Notice", description = "숭실대 공지사항 API")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    @Operation(summary = "최신 공지사항 목록 조회")
    public ApiResponse<NoticeListResponse> getNotices(
            @RequestParam(required = false)
            @Parameter(description = "카테고리 필터 (학사/장학/국제교류 등). 비워두면 전체.", example = "학사")
            String category,
            @RequestParam(required = false, defaultValue = "1")
            @Parameter(description = "페이지 번호 (1부터)", example = "1")
            int page
    ) {
        return ApiResponse.success(noticeService.getRecentNotices(category, page));
    }

    @GetMapping("/search")
    @Operation(summary = "공지사항 키워드 검색")
    public ApiResponse<NoticeListResponse> searchNotices(
            @RequestParam
            @Parameter(description = "검색 키워드 (1~64자)", required = true, example = "장학금")
            String keyword,
            @RequestParam(required = false)
            @Parameter(description = "카테고리 필터 (선택)", example = "장학")
            String category,
            @RequestParam(required = false, defaultValue = "1")
            @Parameter(description = "페이지 번호 (1부터)", example = "1")
            int page
    ) {
        return ApiResponse.success(noticeService.searchNotices(keyword, category, page));
    }

    @GetMapping("/categories")
    @Operation(summary = "공지사항 카테고리 목록 조회")
    public ApiResponse<NoticeCategoriesResponse> getCategories() {
        return ApiResponse.success(noticeService.getCategories());
    }

    @GetMapping("/department")
    @Operation(summary = "특정 학과/부서 공지사항 조회")
    public ApiResponse<NoticeListResponse> getDepartmentNotices(
            @RequestParam
            @Parameter(description = "학과/부서 이름 (예: 컴퓨터학부, 장학팀)", required = true, example = "컴퓨터학부")
            String department,
            @RequestParam(required = false, defaultValue = "1")
            @Parameter(description = "페이지 번호 (1부터)", example = "1")
            int page
    ) {
        return ApiResponse.success(noticeService.getDepartmentNotices(department, page));
    }
}
