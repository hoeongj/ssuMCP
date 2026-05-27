package com.ssuai.domain.saint.dto;

/**
 * One row of the 학기별 성적 history table (ZCMB3W0017 상단 표). 14
 * columns in the page; the row-selection toggle (cc=0) is parser-skipped
 * so the record carries the 13 data columns.
 *
 * <p>Every {@code Double} value is the upstream-rendered string parsed
 * to a plain decimal: e.g. "0.00", "3.56", "94.00". Boolean-style cells
 * (학사경고여부 / 상담여부 / 유급) come from the page as blank cells
 * for the normal-academic case; the parser maps blank to {@code false},
 * any visible "Y" / "유" / "X" mark to {@code true}.
 *
 * <p>{@code rankInTerm} / {@code rankOverall} are kept as raw strings
 * because the page renders them as "70/140" (HTML-encoded as
 * "70&amp;#x2f;140") — splitting into numerator/denominator integers is
 * a presentation-layer concern, not the connector's.
 */
public record TermGpa(
        int year,
        String term,
        double requestedCredits,
        double earnedCredits,
        double passFailCredits,
        double gpa,
        double gpaSum,
        double arithmeticAverage,
        String rankInTerm,
        String rankOverall,
        boolean academicWarning,
        boolean counseling,
        boolean repeatedYear
) {

    public TermGpa {
        if (term == null || term.isBlank()) {
            throw new IllegalArgumentException("term is required");
        }
    }

    /**
     * Stable key used by {@code GradesResponse.detailsByTerm} to match a
     * history row to its per-course detail list. Format: {@code "${year}-${term}"}.
     */
    public String termKey() {
        return year + "-" + term;
    }
}
