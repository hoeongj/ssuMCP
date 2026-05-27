package com.ssuai.domain.library.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.domain.library.service.LibraryBookService;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/library")
@Tag(name = "Library", description = "Library book search API")
public class LibraryBookController {

    private final LibraryBookService libraryBookService;

    public LibraryBookController(LibraryBookService libraryBookService) {
        this.libraryBookService = libraryBookService;
    }

    @GetMapping("/books")
    @Operation(summary = "Search the Soongsil University central library catalog")
    public ApiResponse<LibraryBookSearchResponse> searchBooks(
            @RequestParam
            @Parameter(description = "검색어 (제목/저자/출판 정보 부분 일치). 64자 이하.", required = true, example = "파이썬")
            String query,
            @RequestParam(required = false, defaultValue = "0")
            @Parameter(description = "페이지 (0-based)", example = "0")
            int page,
            @RequestParam(required = false, defaultValue = "10")
            @Parameter(description = "페이지당 결과 수 (1~20). 21 이상은 20으로 캡.", example = "10")
            int size
    ) {
        return ApiResponse.success(libraryBookService.search(query, page, size));
    }
}
