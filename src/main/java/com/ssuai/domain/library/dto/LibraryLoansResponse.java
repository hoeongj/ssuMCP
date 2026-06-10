package com.ssuai.domain.library.dto;

import java.util.List;

/**
 * @param message user-facing explanation when there are no loans, otherwise null
 */
public record LibraryLoansResponse(
        int total,
        List<LibraryLoanItem> loans,
        String message
) {

    public LibraryLoansResponse(int total, List<LibraryLoanItem> loans) {
        this(total, loans, total == 0 ? "현재 대출 중인 도서가 없습니다." : null);
    }
}
