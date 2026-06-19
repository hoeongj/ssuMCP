package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.Decision;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.MatchedRequirement;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse.RequirementResult;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingClient;
import com.ssuai.domain.academic.embedding.EmbeddedChunk;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;

class AcademicPolicyServiceTests {

    private final AcademicPolicyCorpusCache corpusCache = mock(AcademicPolicyCorpusCache.class);
    private final AcademicEmbeddingClient embeddingClient = mock(AcademicEmbeddingClient.class);
    private final AcademicQuestionClassifier classifier = new AcademicQuestionClassifier();
    private final AcademicPolicyService service =
            new AcademicPolicyService(corpusCache, embeddingClient, classifier);

    @Test
    void searchReturnsScoredEvidenceFromOfficialSnapshot() {
        AcademicPolicySource source = source("graduation", "졸업사정 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, "졸업요건은 전공 학점, 교양 학점, 채플을 함께 확인한다.");
        when(corpusCache.embeddedCorpus(false)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));

        var response = service.search("졸업 전공 학점", "graduation", 3, false);

        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().getFirst().title()).isEqualTo("졸업사정 안내");
        assertThat(response.evidence().getFirst().matchedTerms()).contains("졸업", "전공", "학점");
        assertThat(response.embeddingUsed()).isFalse();
        assertThat(response.fusionMethod()).isEqualTo("lexical");
    }

    @Test
    void scholarshipCheckReturnsEligibleWhenAllRequirementsPass() {
        AcademicPolicySource source = source("scholarship", "외국인 유학생 장학금 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, """
                외국인 유학생 장학금은 외국인 유학생이어야 하며,
                2025학년도 이후 입학자, TOPIK 4급 이상, GPA 3.0 이상,
                취득학점 12학점 이상 기준을 충족해야 한다.
                """);
        when(corpusCache.embeddedCorpus(true)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));

        var response = service.checkScholarshipPolicy(
                "외국인 유학생 장학금 TOPIK GPA 취득학점",
                3.4d,
                15,
                2025,
                4,
                true,
                true,
                5);

        assertThat(response.decision()).isEqualTo(Decision.ELIGIBLE);
        assertThat(response.inputFacts()).contains(
                "gpa=3.4", "earnedCredits=15", "admissionYear=2025", "topikLevel=4",
                "internationalStudent=true");
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.matchedRequirements())
                .extracting(MatchedRequirement::result)
                .containsOnly(RequirementResult.OK);
        assertThat(requirement(response, "GPA/평점 기준").required()).isEqualTo("GPA >= 3");
        assertThat(requirement(response, "GPA/평점 기준").userValue()).isEqualTo(3.4d);
        assertThat(requirement(response, "취득학점 기준").required()).isEqualTo("earnedCredits >= 12");
        assertThat(requirement(response, "취득학점 기준").userValue()).isEqualTo(15);
        assertThat(requirement(response, "입학연도 기준").required()).isEqualTo("admissionYear >= 2025");
        assertThat(requirement(response, "TOPIK 급수 기준").required()).isEqualTo("topikLevel >= 4");
        assertThat(requirement(response, "대상 학생 유형").required()).isEqualTo("외국인 유학생");
    }

    @Test
    void scholarshipCheckReturnsNotEligibleWhenAnyRequirementFails() {
        AcademicPolicySource source = source("scholarship", "교내장학금 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(
                source, "백마성적우수장학금은 GPA 3.5 이상, 취득학점 15학점 이상을 충족해야 한다.");
        when(corpusCache.embeddedCorpus(false)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));

        var response = service.checkScholarshipPolicy(
                "백마성적우수장학금 GPA 취득학점",
                3.2d,
                15,
                null,
                null,
                false,
                false,
                5);

        assertThat(response.decision()).isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(requirement(response, "GPA/평점 기준").result()).isEqualTo(RequirementResult.FAIL);
        assertThat(requirement(response, "GPA/평점 기준").userValue()).isEqualTo(3.2d);
        assertThat(requirement(response, "GPA/평점 기준").required()).isEqualTo("GPA >= 3.5");
        assertThat(requirement(response, "취득학점 기준").result()).isEqualTo(RequirementResult.OK);
    }

    @Test
    void scholarshipCheckReturnsInsufficientEvidenceWhenRequiredInputIsMissing() {
        AcademicPolicySource source = source("scholarship", "교내장학금 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(
                source, "백마성적우수장학금은 GPA 3.5 이상, 취득학점 15학점 이상을 충족해야 한다.");
        when(corpusCache.embeddedCorpus(false)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));

        var response = service.checkScholarshipPolicy(
                "백마성적우수장학금 GPA 취득학점",
                null,
                15,
                null,
                null,
                false,
                false,
                5);

        assertThat(response.decision()).isEqualTo(Decision.INSUFFICIENT_EVIDENCE);
        assertThat(requirement(response, "GPA/평점 기준").result()).isEqualTo(RequirementResult.UNKNOWN);
        assertThat(requirement(response, "GPA/평점 기준").userValue()).isNull();
        assertThat(requirement(response, "취득학점 기준").result()).isEqualTo(RequirementResult.OK);
    }

    @Test
    void scholarshipCheckReturnsInsufficientEvidenceWhenPolicyThresholdIsMissing() {
        AcademicPolicySource source = source("scholarship", "교내장학금 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, "백마성적우수장학금은 성적과 취득학점 기준을 확인한다.");
        when(corpusCache.embeddedCorpus(true)).thenReturn(EmbeddedCorpus.lexicalOnly(snapshot));

        var response = service.checkScholarshipPolicy(
                "백마성적우수장학금",
                4.1d,
                15,
                null,
                null,
                false,
                true,
                5);

        assertThat(response.inputFacts()).contains("gpa=4.1", "earnedCredits=15", "internationalStudent=false");
        assertThat(response.decision()).isEqualTo(Decision.INSUFFICIENT_EVIDENCE);
        assertThat(response.matchedRequirements())
                .extracting(MatchedRequirement::result)
                .contains(RequirementResult.UNKNOWN);
        assertThat(response.evidence()).isNotEmpty();
    }

    @Test
    void hybridSearchSurfacesSemanticOnlyChunkViaRrf() {
        // Query shares NO literal token with the doc ("필요한 학점" vs "이수 단위"),
        // so lexical scores 0. Only the embedding (stubbed high cosine) can surface it.
        AcademicPolicySource source = source("graduation", "졸업 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, "본교 학사과정 이수 단위는 총 130을 충족해야 한다.");
        EmbeddedChunk chunk = new EmbeddedChunk(
                source, 0, "본교 학사과정 이수 단위는 총 130을 충족해야 한다.", new float[] {1.0f, 0.0f});
        when(corpusCache.embeddedCorpus(false))
                .thenReturn(new EmbeddedCorpus(snapshot, List.of(chunk), true));
        when(embeddingClient.embedQuery("졸업에 필요한 점수"))
                .thenReturn(new float[] {1.0f, 0.0f}); // cosine 1.0 with the chunk

        var response = service.search("졸업에 필요한 점수", "graduation", 3, false);

        assertThat(response.embeddingUsed()).isTrue();
        assertThat(response.fusionMethod()).isEqualTo("rrf");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().getFirst().snippet()).contains("이수 단위");
    }

    @Test
    void hybridFallsBackToLexicalWhenQueryEmbeddingFails() {
        AcademicPolicySource source = source("graduation", "졸업사정 안내");
        AcademicPolicyCorpusSnapshot snapshot = snapshot(source, "졸업요건은 전공 학점, 교양 학점, 채플을 함께 확인한다.");
        EmbeddedChunk chunk = new EmbeddedChunk(
                source, 0, "졸업요건은 전공 학점, 교양 학점, 채플을 함께 확인한다.", new float[] {1.0f, 0.0f});
        when(corpusCache.embeddedCorpus(false))
                .thenReturn(new EmbeddedCorpus(snapshot, List.of(chunk), true));
        when(embeddingClient.embedQuery("졸업 전공 학점")).thenReturn(new float[0]); // embed failed

        var response = service.search("졸업 전공 학점", "graduation", 3, false);

        assertThat(response.embeddingUsed()).isFalse();
        assertThat(response.fusionMethod()).isEqualTo("lexical");
        assertThat(response.evidence()).hasSize(1);
    }

    private static AcademicPolicyCorpusSnapshot snapshot(AcademicPolicySource source, String text) {
        AcademicPolicyDocument document = new AcademicPolicyDocument(
                source,
                text,
                true,
                false,
                Instant.parse("2026-06-06T00:00:00Z"),
                "hash");
        return new AcademicPolicyCorpusSnapshot(
                List.of(source),
                List.of(document),
                true,
                false,
                Instant.parse("2026-06-06T00:00:00Z"));
    }

    private static AcademicPolicySource source(String category, String title) {
        return new AcademicPolicySource(
                "source-1",
                title,
                category,
                "official-page",
                "https://ssu.ac.kr",
                "https://ssu.ac.kr",
                "official-page",
                null,
                LocalDate.of(2026, 6, 6),
                true,
                "LIVE_SOURCE",
                title);
    }

    private static MatchedRequirement requirement(
            com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse response,
            String name) {
        return response.matchedRequirements().stream()
                .filter(requirement -> requirement.requirement().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
