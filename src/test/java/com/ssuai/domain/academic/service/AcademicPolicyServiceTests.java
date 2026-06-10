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
    void scholarshipCheckBuildsFactsIntoQuery() {
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
}
