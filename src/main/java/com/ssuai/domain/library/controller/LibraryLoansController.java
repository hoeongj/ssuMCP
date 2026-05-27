package com.ssuai.domain.library.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/library/loans")
@Tag(name = "Library", description = "Library loans")
public class LibraryLoansController {

    private final LibraryLoansService loansService;

    public LibraryLoansController(LibraryLoansService loansService) {
        this.loansService = loansService;
    }

    @GetMapping
    @Operation(summary = "내 도서관 대출 현황 조회")
    public ApiResponse<LibraryLoansResponse> getLoans(HttpServletRequest httpRequest) {
        String sessionKey = httpRequest.getSession().getId();
        return ApiResponse.success(loansService.getLoansForSession(sessionKey));
    }
}
