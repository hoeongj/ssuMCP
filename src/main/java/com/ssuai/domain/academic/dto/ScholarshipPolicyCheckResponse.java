package com.ssuai.domain.academic.dto;

import java.util.List;

public record ScholarshipPolicyCheckResponse(
        String query,
        List<String> inputFacts,
        Decision decision,
        List<MatchedRequirement> matchedRequirements,
        String summary,
        List<String> caveats,
        List<AcademicPolicyEvidence> evidence) {

    public enum Decision {
        ELIGIBLE,
        NOT_ELIGIBLE,
        INSUFFICIENT_EVIDENCE
    }

    public enum RequirementResult {
        OK,
        FAIL,
        UNKNOWN
    }

    public record MatchedRequirement(
            String requirement,
            String required,
            Object userValue,
            RequirementResult result) {
    }
}
