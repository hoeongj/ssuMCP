package com.ssuai.domain.academic.dto;

import java.util.List;

import com.ssuai.domain.saint.dto.GraduationStatus;

public record GraduationPolicyEvaluationResponse(
        GraduationStatus graduationStatus,
        AcademicQuestionClassificationResponse classification,
        AcademicPolicyBriefResponse policyBrief,
        // Numeric disagreements between the u-SAINT assessment and the cited policy
        // evidence. Always present; empty when the two agree or the evidence states no
        // comparable figure (the common case for the seed corpus, which deliberately
        // carries no hard numbers). See GraduationPolicyMismatchDetector / ADR 0073.
        List<GraduationPolicyMismatchWarning> mismatchWarnings,
        List<String> nextChecks) {

    public GraduationPolicyEvaluationResponse {
        mismatchWarnings = mismatchWarnings == null ? List.of() : List.copyOf(mismatchWarnings);
        nextChecks = nextChecks == null ? List.of() : List.copyOf(nextChecks);
    }
}
