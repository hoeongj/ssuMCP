package com.ssuai.domain.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.academic.connector.AcademicPolicyConnector;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingClient;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingProperties;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;

/**
 * Regression for the 2026-06-11 prod crash loop: embedding enrichment runs on
 * the @PostConstruct startup path, so no enrichment failure of any exception
 * type may escape — it must degrade to a lexical-only corpus instead.
 */
class AcademicPolicyCorpusCacheTests {

    private static final AcademicPolicySource SOURCE = new AcademicPolicySource(
            "undergraduate-bylaw", "학칙시행세칙", "graduation", "rule",
            "https://rule.example", "https://rule.example/full", "SEQ_HISTORY=1",
            "2026-01-01", null, true, "LIVE_SOURCE", "test");

    private static AcademicPolicyCorpusSnapshot snapshot() {
        AcademicPolicyDocument document = new AcademicPolicyDocument(
                SOURCE, "졸업 학점은 133학점이다.", false, false, Instant.now(), "hash");
        return new AcademicPolicyCorpusSnapshot(
                List.of(SOURCE), List.of(document), false, false, Instant.now());
    }

    private static AcademicPolicyConnector connector() {
        return live -> snapshot();
    }

    /** Usable-looking client whose embed call blows up with a non-RestClientException. */
    private static AcademicEmbeddingClient throwingClient() {
        AcademicEmbeddingProperties properties = new AcademicEmbeddingProperties();
        properties.setEnabled(true);
        properties.setApiKey("key");
        return new AcademicEmbeddingClient(properties) {
            @Override
            public List<float[]> embed(List<String> texts) {
                throw new IllegalArgumentException("invalid header value: \"Bearer key\n\"");
            }
        };
    }

    @Test
    void startupLoadSurvivesEmbeddingFailureAndDegradesToLexical() {
        AcademicPolicyCorpusCache cache =
                new AcademicPolicyCorpusCache(connector(), throwingClient(), false);

        assertThatCode(cache::loadFastFallbackCorpus).doesNotThrowAnyException();

        EmbeddedCorpus corpus = cache.embeddedCorpus(false);
        assertThat(corpus.embeddingActive()).isFalse();
        assertThat(corpus.snapshot().documents()).hasSize(1);
    }

    @Test
    void refreshSurvivesEmbeddingFailureAndDegradesToLexical() {
        AcademicPolicyCorpusCache cache =
                new AcademicPolicyCorpusCache(connector(), throwingClient(), true);

        assertThatCode(cache::refreshFromOfficialSources).doesNotThrowAnyException();
        assertThat(cache.embeddedCorpus(false).embeddingActive()).isFalse();
    }
}
