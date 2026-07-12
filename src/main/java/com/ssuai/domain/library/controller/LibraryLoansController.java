package com.ssuai.domain.library.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.library.auth.LibrarySessionKeyResolver;
import com.ssuai.domain.library.dto.LibraryLoansResponse;
import com.ssuai.domain.library.service.LibraryLoansService;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.response.ApiResponse;

@RestController
@RequestMapping("/api/library/loans")
@Tag(name = "Library", description = "Library loans")
public class LibraryLoansController {

    private final LibraryLoansService loansService;
    private final LibrarySessionKeyResolver sessionKeyResolver;

    public LibraryLoansController(
            LibraryLoansService loansService,
            LibrarySessionKeyResolver sessionKeyResolver) {
        this.loansService = loansService;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    @GetMapping
    @Operation(summary = "내 도서관 대출 현황 조회")
    public ApiResponse<LibraryLoansResponse> getLoans(HttpServletRequest httpRequest) {
        // Resolves the persistent library-session cookie first (survives redeploys/pod
        // switches), falling back to a legacy servlet session id (ADR 0096). Unlike the old
        // httpRequest.getSession().getId() call this never mints a fresh servlet session as a
        // side effect — an absent key is a real 401, not a silently-minted empty session.
        String sessionKey = sessionKeyResolver.resolve(httpRequest)
                .orElseThrow(LibraryAuthRequiredException::new);
        return ApiResponse.success(loansService.getLoansForSession(sessionKey));
    }
}
