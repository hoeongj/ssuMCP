package com.ssuai.domain.library.dto;

import java.util.List;

public record LibraryLoansResponse(
        int total,
        List<LibraryLoanItem> loans
) {
}
