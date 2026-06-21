package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicySearchResponse;
import com.ssuai.domain.academic.dto.AcademicQuestionClassificationResponse;
import com.ssuai.domain.academic.dto.GraduationPolicyEvaluationResponse;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.Decision;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.MatchedRequirement;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.RequirementResult;
import com.ssuai.domain.academic.service.AcademicPolicyService;
import com.ssuai.domain.academic.service.AcademicQuestionClassifier;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.service.SaintGraduationService;

class AcademicPolicyMcpToolsTests {

    private static final String SESSION_ID = "test-session";
    private static final String STUDENT_ID = "20221528";

    private AcademicPolicyService policyService;
    private SaintGraduationService graduationService;
    private McpAuthHelper authHelper;
    private AcademicPolicyMcpTools tools;

    @BeforeEach
    void setUp() {
        policyService = mock(AcademicPolicyService.class);
        graduationService = mock(SaintGraduationService.class);
        authHelper = mock(McpAuthHelper.class);
        tools = new AcademicPolicyMcpTools(policyService, graduationService, authHelper);
    }

    @Test
    void classifyDelegatesToClassifier() {
        AcademicQuestionClassifier classifier = mock(AcademicQuestionClassifier.class);
        var expected = new AcademicQuestionClassificationResponse(
                "졸업", "GRADUATION_POLICY", 0.8d, List.of("graduation"), List.of("evaluate_graduation_with_policy"), "");
        when(policyService.classifier()).thenReturn(classifier);
        when(classifier.classify("졸업")).thenReturn(expected);

        var response = tools.classifyAcademicQuestion("졸업");

        assertThat(response).isSameAs(expected);
    }

    @Test
    void searchDelegatesToPolicyService() {
        var expected = AcademicPolicySearchResponse.of(
                "장학", "scholarship", true, true, false, "live", false, "lexical",
                Instant.parse("2026-06-06T00:00:00Z"),
                1, 0, List.of(), List.of());
        when(policyService.search("장학", "scholarship", 5, true)).thenReturn(expected);

        var response = tools.searchAcademicPolicySources("장학", "scholarship", 5, true);

        assertThat(response).isSameAs(expected);
        assertThat(response.empty()).isTrue();
        assertThat(response.note()).isEqualTo("관련 공식 출처를 찾지 못했어요.");
    }

    @Test
    void scholarshipPolicyToolReturnsStructuredJudgment() {
        var expected = new ScholarshipPolicyCheckResponse(
                "장학",
                List.of("gpa=4.0"),
                Decision.ELIGIBLE,
                List.of(new MatchedRequirement("GPA/평점 기준", "GPA >= 3.5", 4.0d, RequirementResult.OK)),
                "summary",
                List.of(),
                List.of());
        when(policyService.checkScholarshipPolicy("장학", 4.0d, 15, 2025, 4, true, false, 5))
                .thenReturn(expected);

        var response = tools.checkScholarshipPolicy("장학", 4.0d, 15, 2025, 4, true, false, 5);

        assertThat(response).isSameAs(expected);
        assertThat(response.decision()).isEqualTo(Decision.ELIGIBLE);
        assertThat(response.matchedRequirements()).hasSize(1);
        verify(policyService).checkScholarshipPolicy("장학", 4.0d, 15, 2025, 4, true, false, 5);
    }

    @Test
    void graduationPolicyToolReturnsAuthRequiredWithoutCallingServices() {
        McpPrivateToolResponse<GraduationPolicyEvaluationResponse> stub = McpPrivateToolResponse.authRequired(
                null, "SAINT", "https://login.url", Instant.parse("2026-06-06T00:00:00Z"));
        when(authHelper.resolvePrincipal(null, McpProviderType.SAINT)).thenReturn(Optional.empty());
        when(authHelper.<GraduationPolicyEvaluationResponse>buildAuthRequired(null, McpProviderType.SAINT))
                .thenReturn(stub);

        var response = tools.evaluateGraduationWithPolicy(null, false, null);

        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(graduationService);
    }

    @Test
    void graduationPolicyToolCombinesSaintStatusAndPolicyBrief() {
        GraduationStatus status = new GraduationStatus(false, "", "", 3, 100, 133, List.of());
        AcademicQuestionClassifier classifier = new AcademicQuestionClassifier();
        AcademicPolicyBriefResponse brief = new AcademicPolicyBriefResponse("졸업", "graduation", "summary", List.of(), List.of());
        when(authHelper.resolvePrincipal(SESSION_ID, McpProviderType.SAINT))
                .thenReturn(Optional.of(new McpAuthHelper.ResolvedPrincipal(STUDENT_ID, SESSION_ID)));
        when(graduationService.fetchGraduationRequirements(STUDENT_ID)).thenReturn(status);
        when(policyService.classifier()).thenReturn(classifier);
        when(policyService.brief("졸업", "graduation", 5, true)).thenReturn(brief);

        var response = tools.evaluateGraduationWithPolicy("졸업", true, SESSION_ID);

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.data().graduationStatus()).isSameAs(status);
        assertThat(response.data().policyBrief()).isSameAs(brief);
        verify(graduationService).fetchGraduationRequirements(STUDENT_ID);
    }
}
