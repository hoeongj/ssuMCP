package com.ssuai.domain.academic.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits policy document text into overlapping chunks.
 *
 * <p>Extracted from AcademicPolicyService so the lexical search path and the
 * embedding enrichment path chunk identically — both reference chunks by the
 * same {@code (sourceId, chunkIndex)} key, which is what lets RRF fuse the two
 * rankings over the same candidate set.
 */
public final class AcademicTextChunker {

    public static final int CHUNK_SIZE = 700;
    public static final int CHUNK_OVERLAP = 160;

    private AcademicTextChunker() {
    }

    public static List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= CHUNK_SIZE) {
            return List.of(clean);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(clean.length(), start + CHUNK_SIZE);
            chunks.add(clean.substring(start, end));
            if (end == clean.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }
}
