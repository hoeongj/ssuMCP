package com.ssuai.domain.library.connector;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.LibraryLoanItem;
import com.ssuai.domain.library.dto.LibraryLoansResponse;

@Component
@ConditionalOnProperty(name = "ssuai.connector.library-loans", havingValue = "mock", matchIfMissing = true)
public class MockLibraryLoansConnector implements LibraryLoansConnector {

    @Override
    public LibraryLoansResponse fetchLoans(String token) {
        LocalDate today = LocalDate.now();
        List<LibraryLoanItem> items = List.of(
                new LibraryLoanItem(1001L, "스프링 부트 핵심 가이드", "장정우",
                        "005.133J38 장7362스", today.minusDays(5), today.plusDays(9),
                        false, true),
                new LibraryLoanItem(1002L, "클린 코드", "로버트 C. 마틴",
                        "005.1 마8879클", today.minusDays(12), today.plusDays(2),
                        false, false)
        );
        return new LibraryLoansResponse(items.size(), items);
    }
}
