package com.ssuai.domain.academic.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persistence-backed embedding store. Looks up vectors by {@code (chunkHash, model)},
 * embeds only the misses, and persists them so a pod restart or scheduled refresh no
 * longer re-embeds the whole corpus — the re-embed that exhausted the Gemini
 * free-tier daily request quota (1000/day) and pinned policy search to the
 * lexical-only fallback (prod 2026-06-18).
 *
 * <p>Steady state, every chunk is a cache hit, so a refresh makes zero API calls.
 * The only embedding cost is the first time a chunk (or a new model) is seen.
 */
@Component
public class PersistentAcademicEmbeddingStore implements AcademicEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(PersistentAcademicEmbeddingStore.class);

    private final AcademicEmbeddingClient client;
    private final AcademicEmbeddingRepository repository;
    private final AcademicEmbeddingProperties properties;

    public PersistentAcademicEmbeddingStore(
            AcademicEmbeddingClient client,
            AcademicEmbeddingRepository repository,
            AcademicEmbeddingProperties properties) {
        this.client = client;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public boolean isUsable() {
        return client.isUsable();
    }

    @Override
    public List<float[]> embed(List<String> chunkTexts) {
        if (!client.isUsable() || chunkTexts == null || chunkTexts.isEmpty()) {
            return List.of();
        }
        String model = properties.getModel();
        int dimensions = properties.getDimensions();

        // The (hash, model) pair is the cache key; hash every chunk up front.
        List<String> hashes = new ArrayList<>(chunkTexts.size());
        for (String text : chunkTexts) {
            hashes.add(sha256(text));
        }

        // Load whatever is already persisted for this model. A cached vector whose
        // stored dimension no longer matches the configured one (the dimensions
        // property changed under a fixed model name) is ignored, so it is re-embedded
        // at the current dimension and its row replaced — otherwise cosine would
        // compare vectors of different lengths.
        Map<String, float[]> byHash = new HashMap<>();
        for (AcademicEmbeddingEntity entity : repository.findByModelAndChunkHashIn(model, hashes.stream().distinct().toList())) {
            if (entity.getDimensions() == dimensions) {
                byHash.put(entity.getChunkHash(), AcademicVectorCodec.decode(entity.getEmbedding()));
            }
        }

        // Collect the distinct misses with their text, preserving first-seen order.
        Map<String, String> missTextByHash = new LinkedHashMap<>();
        for (int i = 0; i < hashes.size(); i++) {
            String hash = hashes.get(i);
            if (!byHash.containsKey(hash)) {
                missTextByHash.putIfAbsent(hash, chunkTexts.get(i));
            }
        }

        if (!missTextByHash.isEmpty()) {
            List<String> missHashes = new ArrayList<>(missTextByHash.keySet());
            List<String> missTexts = new ArrayList<>(missTextByHash.values());
            List<float[]> fresh = client.embed(missTexts);
            if (fresh.size() != missTexts.size()) {
                // Misses could not be embedded (e.g. quota). Persist nothing; degrade.
                log.warn("academic-embedding store: {} missing chunk(s) could not be embedded; using lexical-only",
                        missTexts.size());
                return List.of();
            }
            List<AcademicEmbeddingEntity> toPersist = new ArrayList<>(missHashes.size());
            Instant now = Instant.now();
            for (int i = 0; i < missHashes.size(); i++) {
                float[] vector = fresh.get(i);
                byHash.put(missHashes.get(i), vector);
                toPersist.add(new AcademicEmbeddingEntity(
                        missHashes.get(i), model, vector.length, AcademicVectorCodec.encode(vector), now));
            }
            repository.saveAll(toPersist);
            log.debug("academic-embedding store: embedded {} new chunk(s), {} from cache",
                    toPersist.size(), chunkTexts.size() - toPersist.size());
        } else {
            log.debug("academic-embedding store: all {} chunk(s) from cache; no API calls", chunkTexts.size());
        }

        // Assemble one vector per chunk in the caller's order.
        List<float[]> result = new ArrayList<>(chunkTexts.size());
        for (String hash : hashes) {
            float[] vector = byHash.get(hash);
            if (vector == null) {
                return List.of(); // defensive: every hash should be resolved by now
            }
            result.add(vector);
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
