package com.ssuai.domain.saint.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        Double gpa,
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
        gpa = gpa == null ? null : round2(gpa);
        arithmeticAverage = round2(arithmeticAverage);
        gpaSum = round2(gpaSum);
    }

    public String termKey() {
        return year + "-" + term;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    /**
     * Credits included in this term's GPA calculation. P/F-only terms return
     * zero here and should normally have {@code gpa=null}.
     */
    @JsonProperty("gpaCredits")
    public double gpaCredits() {
        return Math.max(0.0d, earnedCredits - passFailCredits);
    }
}
