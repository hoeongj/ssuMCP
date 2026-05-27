package com.ssuai.domain.library.dto;

import java.time.LocalDate;

/**
 * Single checkout record returned by /pyxis-api/1/api/charges.
 *
 * @param id         Pyxis charge (loan) ID
 * @param title      Book title
 * @param author     Author(s)
 * @param callNumber Library call number
 * @param loanDate   Date the book was checked out
 * @param dueDate    Return due date
 * @param isOverdue  Whether the due date has passed
 * @param isRenewable Whether renewal is allowed by library policy
 */
public record LibraryLoanItem(
        long id,
        String title,
        String author,
        String callNumber,
        LocalDate loanDate,
        LocalDate dueDate,
        boolean isOverdue,
        boolean isRenewable
) {
}
