package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

class AcademicEmbeddingClientTests {

    @Test
    void truncatesToRequestedDimensionsAndNormalizesToUnitLength() {
        // 4-d raw vector, request 2 dimensions (Matryoshka prefix)
        float[] result = AcademicEmbeddingClient.normalizeAndTruncate(
                List.of(3.0, 4.0, 9.0, 9.0), 2);

        assertThat(result).hasSize(2);
        double norm = Math.sqrt(result[0] * result[0] + result[1] * result[1]);
        assertThat(norm).isCloseTo(1.0, within(1e-6));
        // 3,4 normalized -> 0.6, 0.8
        assertThat(result[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(result[1]).isCloseTo(0.8f, within(1e-6f));
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertThat(AcademicEmbeddingClient.normalizeAndTruncate(List.of(), 768)).isEmpty();
        assertThat(AcademicEmbeddingClient.normalizeAndTruncate(null, 768)).isEmpty();
    }

    @Test
    void disabledClientReturnsEmptyWithoutCallingNetwork() {
        AcademicEmbeddingProperties props = new AcademicEmbeddingProperties();
        props.setEnabled(false);
        AcademicEmbeddingClient client = new AcademicEmbeddingClient(props);

        assertThat(client.isUsable()).isFalse();
        assertThat(client.embed(List.of("anything"))).isEmpty();
        assertThat(client.embedQuery("anything")).isEmpty();
    }

    @Test
    void propertiesTrimCredentialAndUrlAtBinding() {
        // kubectl create secret from echo without -n leaves a trailing LF; an LF
        // inside an Authorization header crashes the JDK HttpClient (prod
        // 2026-06-11). Binding must hand consumers clean values.
        AcademicEmbeddingProperties props = new AcademicEmbeddingProperties();
        props.setApiKey("AIzaSy-example\n");
        props.setBaseUrl(" https://generativelanguage.googleapis.com/v1beta/openai \n");
        props.setModel("gemini-embedding-001\n");
        props.setEnabled(true);

        assertThat(props.getApiKey()).isEqualTo("AIzaSy-example");
        assertThat(props.getBaseUrl()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
        assertThat(props.getModel()).isEqualTo("gemini-embedding-001");
        assertThat(props.isUsable()).isTrue();
    }

    @Test
    void nullCredentialBindsToEmptyAndStaysUnusable() {
        AcademicEmbeddingProperties props = new AcademicEmbeddingProperties();
        props.setApiKey(null);
        props.setEnabled(true);

        assertThat(props.getApiKey()).isEmpty();
        assertThat(props.isUsable()).isFalse();
    }
}
