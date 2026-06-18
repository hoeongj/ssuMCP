package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersistentAcademicEmbeddingStoreTests {

    private static AcademicEmbeddingProperties properties() {
        AcademicEmbeddingProperties properties = new AcademicEmbeddingProperties();
        properties.setEnabled(true);
        properties.setApiKey("key");
        properties.setModel("test-model");
        properties.setDimensions(3); // match the 3-element test vectors
        return properties;
    }

    /** Records embed calls and returns a deterministic vector per input. */
    private static final class RecordingClient extends AcademicEmbeddingClient {
        final List<List<String>> calls = new ArrayList<>();

        RecordingClient(AcademicEmbeddingProperties properties) {
            super(properties);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.add(List.copyOf(texts));
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[] {0.1f, 0.2f, 0.3f});
            }
            return out;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void embedsOnlyMissesAndPersistsThem() {
        AcademicEmbeddingProperties properties = properties();
        RecordingClient client = new RecordingClient(properties);
        AcademicEmbeddingRepository repository = mock(AcademicEmbeddingRepository.class);
        when(repository.findByModelAndChunkHashIn(anyString(), any())).thenReturn(List.of());
        PersistentAcademicEmbeddingStore store =
                new PersistentAcademicEmbeddingStore(client, repository, properties);

        List<float[]> vectors = store.embed(List.of("alpha", "beta"));

        assertThat(vectors).hasSize(2);
        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0)).containsExactly("alpha", "beta");
        ArgumentCaptor<List<AcademicEmbeddingEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getModel()).isEqualTo("test-model");
        assertThat(captor.getValue().get(0).getDimensions()).isEqualTo(3);
    }

    @Test
    void servesCachedChunksWithoutCallingApi() {
        AcademicEmbeddingProperties properties = properties();
        RecordingClient client = new RecordingClient(properties);
        AcademicEmbeddingRepository repository = mock(AcademicEmbeddingRepository.class);
        // Echo back a persisted entity for every requested hash → everything is cached.
        when(repository.findByModelAndChunkHashIn(eq("test-model"), any())).thenAnswer(invocation -> {
            Collection<String> hashes = invocation.getArgument(1);
            List<AcademicEmbeddingEntity> out = new ArrayList<>();
            for (String hash : hashes) {
                out.add(new AcademicEmbeddingEntity(
                        hash, "test-model", 3, AcademicVectorCodec.encode(new float[] {1f, 0f, 0f}), Instant.now()));
            }
            return out;
        });
        PersistentAcademicEmbeddingStore store =
                new PersistentAcademicEmbeddingStore(client, repository, properties);

        List<float[]> vectors = store.embed(List.of("alpha", "beta"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1f, 0f, 0f);
        assertThat(client.calls).isEmpty();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void degradesToEmptyWhenMissesCannotBeEmbedded() {
        AcademicEmbeddingProperties properties = properties();
        AcademicEmbeddingClient failing = new AcademicEmbeddingClient(properties) {
            @Override
            public List<float[]> embed(List<String> texts) {
                return List.of(); // simulates a 429 quota failure
            }
        };
        AcademicEmbeddingRepository repository = mock(AcademicEmbeddingRepository.class);
        when(repository.findByModelAndChunkHashIn(anyString(), any())).thenReturn(List.of());
        PersistentAcademicEmbeddingStore store =
                new PersistentAcademicEmbeddingStore(failing, repository, properties);

        List<float[]> vectors = store.embed(List.of("alpha"));

        assertThat(vectors).isEmpty();
        verify(repository, never()).saveAll(any());
    }
}
