package com.ssuai.domain.academic.dto;

/**
 * A numeric disagreement between the student's u-SAINT graduation assessment
 * and a cited official policy clause. Data-quality signal only: neither source
 * is corrected, the client is asked to advise manual verification.
 *
 * @param requirementKey  stable machine key, e.g. {@code TOTAL_CREDITS}, {@code CHAPEL_SEMESTERS}
 * @param assessmentValue value reported by the u-SAINT graduation assessment
 * @param policyValue     value extracted from the cited policy clause
 * @param evidenceTitle   title of the policy source the value was extracted from
 * @param evidenceHeading chunk-level heading (clause pointer) inside that source
 * @param message         Korean guidance advising the student to verify with the department office
 */
public record GraduationPolicyMismatchWarning(
        String requirementKey,
        float assessmentValue,
        int policyValue,
        String evidenceTitle,
        String evidenceHeading,
        String message) {
}
