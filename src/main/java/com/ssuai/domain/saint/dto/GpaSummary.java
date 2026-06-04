package com.ssuai.domain.saint.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cumulative GPA summary block from ZCMB3W0017. The page renders two
 * such blocks: 학적부 (graduation-requirement basis) and 증명용
 * (external-certificate basis, which differs when courses were retaken
 * or P/F-converted). Both share the same six fields.
 *
 * <p>{@link GradesResponse} carries one of each; the dashboard/frontend
 * decides which to display where. The LLM-side compaction step takes the
 * 학적부 figures only (per task spec §6 #6) — never the per-row detail.
 */
public record GpaSummary(
        double requestedCredits,
        double earnedCredits,
        double gpaSum,
        double gpa,
        double arithmeticAverage,
        double passFailCredits
) {

    public GpaSummary {
        gpa = round2(gpa);
        arithmeticAverage = round2(arithmeticAverage);
        gpaSum = round2(gpaSum);
    }

    /**
     * Credits included in GPA calculation. P/F credits are earned credits,
     * but they do not belong in the GPA denominator.
     */
    @JsonProperty("gpaCredits")
    public double gpaCredits() {
        return Math.max(0.0d, earnedCredits - passFailCredits);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
