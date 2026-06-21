package com.ssuai.domain.mcp.tool;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicySearchResponse;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.dto.AcademicQuestionClassificationResponse;
import com.ssuai.domain.academic.dto.GraduationPolicyEvaluationResponse;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse;
import com.ssuai.domain.academic.service.AcademicPolicyService;
import com.ssuai.domain.auth.mcp.McpProviderType;
import com.ssuai.domain.auth.mcp.dto.McpPrivateToolResponse;
import com.ssuai.domain.saint.dto.GraduationStatus;
import com.ssuai.domain.saint.service.SaintGraduationService;

@Component
public class AcademicPolicyMcpTools {

    private static final Logger log = LoggerFactory.getLogger(AcademicPolicyMcpTools.class);

    private final AcademicPolicyService policyService;
    private final SaintGraduationService graduationService;
    private final McpAuthHelper authHelper;

    public AcademicPolicyMcpTools(
            AcademicPolicyService policyService,
            SaintGraduationService graduationService,
            McpAuthHelper authHelper) {
        this.policyService = policyService;
        this.graduationService = graduationService;
        this.authHelper = authHelper;
    }

    @Tool(
            name = "classify_academic_question",
            description = "학사 질문을 졸업/장학/학사일정/일반 규정 의도로 분류하고, 이어서 호출할 ssuMCP 도구를 추천합니다."
    )
    public AcademicQuestionClassificationResponse classifyAcademicQuestion(
            @ToolParam(description = "분류할 학사 질문. 예: 복수전공 졸업 학점 조건 알려줘.")
            String query) {
        return policyService.classifier().classify(query);
    }

    @Tool(
            name = "search_academic_policy_sources",
            description = "숭실대학교 공식 규정관리시스템과 공식 학사 안내 페이지에서 학칙·졸업·장학 근거 문단을 검색합니다. live=true이면 공식 URL을 즉시 조회하고 실패한 출처만 seed corpus로 대체합니다."
    )
    public AcademicPolicySearchResponse searchAcademicPolicySources(
            @ToolParam(description = "검색어. 예: 복수전공 졸업 학점, 백마성적우수장학금 취득학점.")
            String query,
            @ToolParam(required = false, description = "검색 범위: graduation, scholarship, academic 중 하나. 생략하면 전체.")
            String category,
            @ToolParam(required = false, description = "반환할 근거 수. 기본 5, 최대 10.")
            Integer limit,
            @ToolParam(required = false, description = "true면 공식 원문을 live fetch. false/생략이면 빠른 seed corpus 검색.")
            Boolean live) {
        return policyService.search(query, category, limit, live);
    }

    @Tool(
            name = "get_academic_policy_brief",
            description = "학사 질문에 대한 공식 출처 기반 근거 요약을 반환합니다. 답변 자체가 아니라 출처 URL, 개정 이력, live/fallback 상태가 붙은 evidence를 제공합니다."
    )
    public AcademicPolicyBriefResponse getAcademicPolicyBrief(
            @ToolParam(description = "요약할 학사 질문.")
            String query,
            @ToolParam(required = false, description = "검색 범위: graduation, scholarship, academic 중 하나. 생략하면 전체.")
            String category,
            @ToolParam(required = false, description = "반환할 근거 수. 기본 5, 최대 10.")
            Integer limit,
            @ToolParam(required = false, description = "true면 공식 원문을 live fetch. false/생략이면 빠른 seed corpus 검색.")
            Boolean live) {
        return policyService.brief(query, category, limit, live);
    }

    @Tool(
            name = "check_scholarship_policy",
            description = "장학금 질문과 선택 입력 조건(GPA, 취득학점, 입학연도, TOPIK 등)을 공식 장학 규정·안내 근거와 대조하고 decision/matchedRequirements/evidence를 반환합니다. 개인 장학 수혜 내역은 get_my_scholarships를 함께 사용하세요."
    )
    public ScholarshipPolicyCheckResponse checkScholarshipPolicy(
            @ToolParam(required = false, description = "장학금 질문. 예: 백마성적우수장학금 기준 알려줘.")
            String query,
            @ToolParam(required = false, description = "직전학기 또는 누적 GPA. 장학별 산식이 다를 수 있어 evidence 확인 필요.")
            Double gpa,
            @ToolParam(required = false, description = "직전학기 취득학점.")
            Integer earnedCredits,
            @ToolParam(required = false, description = "입학연도. 외국인 유학생 장학금 등 연도별 기준 확인에 사용.")
            Integer admissionYear,
            @ToolParam(required = false, description = "TOPIK 급수. 외국인 유학생 장학금 기준 확인에 사용.")
            Integer topikLevel,
            @ToolParam(required = false, description = "외국인 유학생 장학금 여부.")
            Boolean internationalStudent,
            @ToolParam(required = false, description = "true면 공식 원문을 live fetch. false/생략이면 빠른 seed corpus 검색.")
            Boolean live,
            @ToolParam(required = false, description = "반환할 근거 수. 기본 5, 최대 10.")
            Integer limit) {
        return policyService.checkScholarshipPolicy(
                query, gpa, earnedCredits, admissionYear, topikLevel, internationalStudent, live, limit);
    }

    @Tool(
            name = "list_academic_policy_sources",
            description = "학사 RAG가 조회하는 공식 출처 목록을 반환합니다. 각 출처의 URL, contentUrl, revision/effectiveDate, live 지원 여부, 검증일을 확인할 수 있습니다."
    )
    public List<AcademicPolicySource> listAcademicPolicySources(
            @ToolParam(required = false, description = "출처 범위: graduation, scholarship, academic 중 하나. 생략하면 전체.")
            String category,
            @ToolParam(required = false, description = "true면 connector가 live corpus 로딩 경로를 사용합니다. 목록만 볼 때는 false/생략 권장.")
            Boolean live) {
        return policyService.listSources(category, live);
    }

    @Tool(
            name = "evaluate_graduation_with_policy",
            description = "인증된 학생의 u-SAINT 졸업요건 상태와 공식 학칙/졸업 안내 근거를 함께 반환합니다. Requires mcp_session_id with the SAINT provider linked via start_auth."
    )
    public McpPrivateToolResponse<GraduationPolicyEvaluationResponse> evaluateGraduationWithPolicy(
            @ToolParam(required = false, description = "추가로 확인할 졸업 질문. 생략하면 졸업요건, 이수학점, 전공/교양 기준을 검색합니다.")
            String question,
            @ToolParam(required = false, description = "true면 공식 원문을 live fetch. false/생략이면 빠른 seed corpus 검색.")
            Boolean live,
            @ToolParam(description = "MCP session ID issued by start_auth(SAINT). If absent or SAINT not linked, returns AUTH_REQUIRED with a loginUrl.")
            String mcp_session_id) {
        return authHelper.resolvePrincipal(mcp_session_id, McpProviderType.SAINT)
                .map(principal -> {
                    log.debug("evaluate_graduation_with_policy: fetching graduation status and policy evidence");
                    String safeQuestion = question == null || question.isBlank()
                            ? "졸업요건 이수학점 전공 교양 다전공 채플"
                            : question;
                    GraduationStatus status = graduationService.fetchGraduationRequirements(principal.studentId());
                    AcademicQuestionClassificationResponse classification =
                            policyService.classifier().classify(safeQuestion);
                    AcademicPolicyBriefResponse brief =
                            policyService.brief(safeQuestion, "graduation", 5, live);
                    GraduationPolicyEvaluationResponse data = new GraduationPolicyEvaluationResponse(
                            status,
                            classification,
                            brief,
                            List.of(
                                    "u-SAINT 졸업사정표의 부족 항목과 policyBrief evidence를 함께 비교하세요.",
                                    "학과별 교육과정, 입학연도별 경과조치, 다전공 여부는 공식 학과 안내와 추가 대조가 필요할 수 있습니다.",
                                    "졸업 가능/불가능을 단정하기 전 학사팀 또는 학과 사무실 확인이 필요한 예외를 표시하세요."));
                    return McpPrivateToolResponse.ok(
                            principal.sessionId(), McpProviderType.SAINT.name(), data);
                })
                .orElseGet(() -> authHelper.buildAuthRequired(mcp_session_id, McpProviderType.SAINT));
    }
}
