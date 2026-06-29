package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

class AcademicEmbeddingClientTests {

    /** Tiny timings so retry/pacing tests run instantly. */
    private static AcademicEmbeddingProperties retryProps() {
        AcademicEmbeddingProperties props = new AcademicEmbeddingProperties();
        props.setEnabled(true);
        props.setApiKey("key");
        props.setDimensions(2);
        props.setBatchIntervalMs(0);
        props.setRetryBackoffMs(1);
        return props;
    }

    private static RestClientResponseException tooManyRequests() {
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null);
    }

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
    void retriesRateLimitedBatchWithBackoffThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AcademicEmbeddingClient client = new AcademicEmbeddingClient(retryProps()) {
            @Override
            EmbeddingResponse callEmbeddings(List<String> batch) {
                if (calls.incrementAndGet() <= 2) {
                    throw tooManyRequests();
                }
                return new EmbeddingResponse(List.of(new EmbeddingResponse.Item(List.of(3.0, 4.0), 0)));
            }
        };

        List<float[]> vectors = client.embed(List.of("text"));

        assertThat(calls.get()).isEqualTo(3);
        assertThat(vectors).hasSize(1);
        assertThat(vectors.getFirst()).hasSize(2);
    }

    @Test
    void givesUpWhenRateLimitOutlastsRetryBudget() {
        AcademicEmbeddingProperties props = retryProps();
        props.setRetryMaxAttempts(1);
        AtomicInteger calls = new AtomicInteger();
        AcademicEmbeddingClient client = new AcademicEmbeddingClient(props) {
            @Override
            EmbeddingResponse callEmbeddings(List<String> batch) {
                calls.incrementAndGet();
                throw tooManyRequests();
            }
        };

        assertThat(client.embed(List.of("text"))).isEmpty();
        assertThat(calls.get()).isEqualTo(2); // initial call + 1 retry
    }

    @Test
    void returnsSuccessfulPrefixWhenALaterBatchFails() {
        // One text per batch: first batch succeeds, the second 429s past its budget.
        // The successful first vector must survive so a persistence-backed caller can
        // warm the corpus incrementally instead of discarding progress (prod 2026-06-29).
        AcademicEmbeddingProperties props = retryProps();
        props.setBatchSize(1);
        props.setRetryMaxAttempts(0); // give up on the first 429
        AtomicInteger calls = new AtomicInteger();
        AcademicEmbeddingClient client = new AcademicEmbeddingClient(props) {
            @Override
            EmbeddingResponse callEmbeddings(List<String> batch) {
                if (calls.incrementAndGet() == 1) {
                    return new EmbeddingResponse(List.of(new EmbeddingResponse.Item(List.of(3.0, 4.0), 0)));
                }
                throw tooManyRequests();
            }
        };

        List<float[]> vectors = client.embed(List.of("first", "second"));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(vectors).hasSize(1);
        assertThat(vectors.getFirst()).hasSize(2);
    }

    @Test
    void nonRateLimitFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        AcademicEmbeddingClient client = new AcademicEmbeddingClient(retryProps()) {
            @Override
            EmbeddingResponse callEmbeddings(List<String> batch) {
                calls.incrementAndGet();
                throw new IllegalStateException("boom");
            }
        };

        assertThat(client.embed(List.of("text"))).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
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
