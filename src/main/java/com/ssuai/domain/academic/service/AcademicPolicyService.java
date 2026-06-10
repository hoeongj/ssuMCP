package com.ssuai.domain.academic.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.ssuai.domain.academic.dto.AcademicPolicyBriefResponse;
import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;
import com.ssuai.domain.academic.dto.AcademicPolicyDocument;
import com.ssuai.domain.academic.dto.AcademicPolicyEvidence;
import com.ssuai.domain.academic.dto.AcademicPolicySearchResponse;
import com.ssuai.domain.academic.dto.AcademicPolicySource;
import com.ssuai.domain.academic.dto.ScholarshipPolicyCheckResponse;
import com.ssuai.domain.academic.embedding.AcademicEmbeddingClient;
import com.ssuai.domain.academic.embedding.AcademicTextChunker;
import com.ssuai.domain.academic.embedding.EmbeddedChunk;
import com.ssuai.domain.academic.embedding.EmbeddedCorpus;

@Service
public class AcademicPolicyService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    /** RRF smoothing constant. 60 is the industry default (Elastic/Azure/Mongo/Weaviate). */
    private static final int RRF_K = 60;
    /** Vector hits considered before fusion — bounded so a large corpus stays cheap. */
    private static final int VECTOR_CANDIDATE_LIMIT = 50;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,.;:!?()\\[\\]{}<>\"'`/|]+");

    private final AcademicPolicyCorpusCache corpusCache;
    private final AcademicEmbeddingClient embeddingClient;
    private final AcademicQuestionClassifier classifier;

    public AcademicPolicyService(
            AcademicPolicyCorpusCache corpusCache,
            AcademicEmbeddingClient embeddingClient,
            AcademicQuestionClassifier classifier) {
        this.corpusCache = corpusCache;
        this.embeddingClient = embeddingClient;
        this.classifier = classifier;
    }

    public List<AcademicPolicySource> listSources(String category, Boolean live) {
        AcademicPolicyCorpusSnapshot snapshot = corpusCache.snapshot(Boolean.TRUE.equals(live));
        String normalizedCategory = normalizeCategory(category);
        return snapshot.sources().stream()
                .filter(source -> matchesCategory(source, normalizedCategory))
                .toList();
    }

    public AcademicPolicySearchResponse search(String query, String category, Integer limit, Boolean live) {
        String safeQuery = query == null ? "" : query.trim();
        String normalizedCategory = normalizeCategory(category);
        int safeLimit = safeLimit(limit);
        boolean callerRequestedLive = Boolean.TRUE.equals(live);

        EmbeddedCorpus corpus = corpusCache.embeddedCorpus(callerRequestedLive);
        AcademicPolicyCorpusSnapshot snapshot = corpus.snapshot();

        List<String> rawTokens = tokens(safeQuery);
        List<String> searchTokens = rawTokens.isEmpty() && normalizedCategory != null
                ? List.of(normalizedCategory)
                : rawTokens;

        // Lexical ranking over chunks shared with the embedding path (same chunk indices).
        List<Candidate> lexicalRanked = lexicalCandidates(snapshot, normalizedCategory, searchTokens);

        float[] queryVector = (corpus.embeddingActive() && !safeQuery.isBlank())
                ? embeddingClient.embedQuery(safeQuery)
                : new float[0];
        boolean embeddingUsed = queryVector.length > 0;
        List<Candidate> ranked = embeddingUsed
                ? fuseWithRrf(lexicalRanked, vectorCandidates(corpus, normalizedCategory, queryVector))
                : lexicalRanked;

        Map<String, AcademicPolicyDocument> documentsBySourceId = documentsBySourceId(snapshot);
        List<AcademicPolicyEvidence> evidence = ranked.stream()
                .limit(safeLimit)
                .map(candidate -> toEvidence(candidate, documentsBySourceId))
                .toList();

        return new AcademicPolicySearchResponse(
                safeQuery,
                normalizedCategory,
                callerRequestedLive,
                callerRequestedLive && snapshot.liveRequested(),
                snapshot.fallbackUsed(),
                corpusType(snapshot),
                embeddingUsed,
                embeddingUsed ? "rrf" : "lexical",
                snapshot.fetchedAt(),
                (int) snapshot.sources().stream()
                        .filter(source -> matchesCategory(source, normalizedCategory))
                        .count(),
                evidence.size(),
                evidence,
                snapshot.sources().stream()
                        .filter(source -> matchesCategory(source, normalizedCategory))
                        .toList());
    }

    public AcademicPolicyBriefResponse brief(String query, String category, Integer limit, Boolean live) {
        AcademicPolicySearchResponse search = search(query, category, limit, live);
        String summary;
        if (search.evidence().isEmpty()) {
            summary = "공식 출처에서 직접 일치하는 근거를 찾지 못했습니다. query를 더 구체화하거나 최신 공지사항도 함께 확인하세요.";
        } else {
            AcademicPolicyEvidence top = search.evidence().getFirst();
            summary = "상위 근거는 " + top.title() + " (" + top.revision() + ")입니다. "
                    + "답변에는 evidence의 url, revision/effectiveDate, live/fallback 상태를 함께 인용하세요.";
        }
        List<String> cautions = new ArrayList<>();
        cautions.add("이 응답은 extractive policy evidence입니다. 최종 판단은 u-SAINT 개인 데이터와 학과별 교육과정을 함께 확인해야 합니다.");
        if (search.fallbackUsed()) {
            cautions.add("일부 출처는 live fetch 실패 또는 live=false로 seed corpus를 사용했습니다.");
        }
        return new AcademicPolicyBriefResponse(
                search.query(),
                search.category(),
                summary,
                cautions,
                search.evidence());
    }

    public ScholarshipPolicyCheckResponse checkScholarshipPolicy(
            String query,
            Double gpa,
            Integer earnedCredits,
            Integer admissionYear,
            Integer topikLevel,
            Boolean internationalStudent,
            Boolean live,
            Integer limit) {
        List<String> facts = new ArrayList<>();
        if (gpa != null) facts.add("gpa=" + gpa);
        if (earnedCredits != null) facts.add("earnedCredits=" + earnedCredits);
        if (admissionYear != null) facts.add("admissionYear=" + admissionYear);
        if (topikLevel != null) facts.add("topikLevel=" + topikLevel);
        if (internationalStudent != null) facts.add("internationalStudent=" + internationalStudent);

        String combinedQuery = buildScholarshipQuery(query, facts);
        AcademicPolicyBriefResponse brief = brief(combinedQuery, "scholarship", limit, live);
        List<String> caveats = new ArrayList<>(brief.cautions());
        caveats.add("장학금은 학기별 공지, 등록 상태, 국가장학금 신청 여부, 중복 수혜 제한에 따라 달라질 수 있습니다.");
        if (Boolean.TRUE.equals(internationalStudent)) {
            caveats.add("외국인 유학생 장학금은 입학연도와 TOPIK 급수별 구간이 달라 공식 안내의 연도별 기준을 우선해야 합니다.");
        }
        return new ScholarshipPolicyCheckResponse(
                combinedQuery,
                facts,
                "입력 조건과 가장 가까운 장학 기준 근거를 반환합니다. 정량 결론은 evidence를 기준으로 보수적으로 판단하세요.",
                caveats,
                brief.evidence());
    }

    public AcademicQuestionClassifier classifier() {
        return classifier;
    }

    // --- ranking ---------------------------------------------------------

    private List<Candidate> lexicalCandidates(
            AcademicPolicyCorpusSnapshot snapshot, String category, List<String> tokens) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (AcademicPolicyDocument document : snapshot.documents()) {
            if (!matchesCategory(document.source(), category)) {
                continue;
            }
            List<String> chunks = AcademicTextChunker.chunk(document.text());
            for (int index = 0; index < chunks.size(); index++) {
                Score score = score(chunks.get(index), document.source(), tokens);
                if (score.value() > 0) {
                    candidates.add(new Candidate(
                            document.source(), index, chunks.get(index), score.value(), score.matchedTerms()));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::lexScore).reversed()
                .thenComparing(candidate -> candidate.source().title()));
        return candidates;
    }

    private List<Candidate> vectorCandidates(EmbeddedCorpus corpus, String category, float[] queryVector) {
        return corpus.chunks().stream()
                .filter(chunk -> matchesCategory(chunk.source(), category))
                .map(chunk -> Map.entry(chunk, chunk.cosineSimilarity(queryVector)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<EmbeddedChunk, Double>comparingByValue().reversed())
                .limit(VECTOR_CANDIDATE_LIMIT)
                .map(entry -> {
                    EmbeddedChunk chunk = entry.getKey();
                    return new Candidate(chunk.source(), chunk.chunkIndex(), chunk.text(), 0, List.of());
                })
                .toList();
    }

    /**
     * Reciprocal Rank Fusion: each candidate scores Σ 1/(k + rank) across the lexical
     * and vector lists. Works on rank positions, not raw scores, so the two
     * incomparable score scales need no normalization.
     */
    private List<Candidate> fuseWithRrf(List<Candidate> lexicalRanked, List<Candidate> vectorRanked) {
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();

        accumulate(lexicalRanked, byKey, rrfScore);
        accumulate(vectorRanked, byKey, rrfScore);

        return rrfScore.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(entry -> byKey.get(entry.getKey()).source().title()))
                .map(entry -> byKey.get(entry.getKey()))
                .toList();
    }

    private static void accumulate(
            List<Candidate> ranked, Map<String, Candidate> byKey, Map<String, Double> rrfScore) {
        for (int i = 0; i < ranked.size(); i++) {
            Candidate candidate = ranked.get(i);
            String key = candidate.source().id() + "#" + candidate.chunkIndex();
            // Prefer the candidate that carries matched lexical terms for richer evidence.
            byKey.merge(key, candidate, (existing, incoming) ->
                    existing.matchedTerms().isEmpty() ? incoming : existing);
            rrfScore.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private static Map<String, AcademicPolicyDocument> documentsBySourceId(AcademicPolicyCorpusSnapshot snapshot) {
        Map<String, AcademicPolicyDocument> map = new LinkedHashMap<>();
        for (AcademicPolicyDocument document : snapshot.documents()) {
            map.putIfAbsent(document.source().id(), document);
        }
        return map;
    }

    private static AcademicPolicyEvidence toEvidence(
            Candidate candidate, Map<String, AcademicPolicyDocument> documentsBySourceId) {
        AcademicPolicySource source = candidate.source();
        AcademicPolicyDocument document = documentsBySourceId.get(source.id());
        boolean live = document != null && document.live();
        boolean fallbackUsed = document != null && document.fallbackUsed();
        return new AcademicPolicyEvidence(
                source.id(),
                source.title(),
                source.category(),
                source.sourceType(),
                source.url(),
                source.revision(),
                source.effectiveDate(),
                live,
                fallbackUsed,
                document != null ? document.fetchedAt() : null,
                candidate.lexScore(),
                source.title() + " #" + (candidate.chunkIndex() + 1),
                snippet(candidate.chunkText(), candidate.matchedTerms()),
                candidate.matchedTerms());
    }

    // --- scoring helpers (lexical) --------------------------------------

    private static Score score(String chunk, AcademicPolicySource source, List<String> tokens) {
        String haystack = normalize(chunk + " " + source.title() + " " + source.category() + " " + source.note());
        Set<String> matched = new LinkedHashSet<>();
        int score = 0;
        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            int count = countOccurrences(haystack, token);
            if (count > 0) {
                matched.add(token);
                score += count;
            }
        }
        if (matched.isEmpty()) {
            return new Score(0, List.of());
        }
        if (source.title().toLowerCase(Locale.ROOT).contains(tokens.getFirst())) {
            score += 2;
        }
        return new Score(score, List.copyOf(matched));
    }

    private static String snippet(String chunk, List<String> matchedTerms) {
        if (chunk.length() <= 360) {
            return chunk;
        }
        int start = 0;
        for (String term : matchedTerms) {
            int index = normalize(chunk).indexOf(term);
            if (index >= 0) {
                start = Math.max(0, index - 120);
                break;
            }
        }
        int end = Math.min(chunk.length(), start + 360);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < chunk.length() ? "..." : "";
        return prefix + chunk.substring(start, end).trim() + suffix;
    }

    private static List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return TOKEN_SPLIT.splitAsStream(normalize(query))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .distinct()
                .limit(12)
                .toList();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String normalized = normalize(category);
        if (normalized.contains("졸업") || normalized.contains("학점") || normalized.contains("graduation")) {
            return "graduation";
        }
        if (normalized.contains("장학") || normalized.contains("scholar")) {
            return "scholarship";
        }
        if (normalized.contains("일정") || normalized.contains("calendar")) {
            return "calendar";
        }
        return normalized;
    }

    private static boolean matchesCategory(AcademicPolicySource source, String category) {
        return category == null
                || "academic".equals(category)
                || source.category().equalsIgnoreCase(category)
                || source.note().toLowerCase(Locale.ROOT).contains(category);
    }

    /**
     * Corpus provenance: "live" only when fetched from official sources without any
     * per-source fallback, "mixed" when live fetch partially fell back to seed,
     * "seed" when the snapshot never touched the official sources.
     */
    private static String corpusType(AcademicPolicyCorpusSnapshot snapshot) {
        if (!snapshot.liveRequested()) {
            return "seed";
        }
        return snapshot.fallbackUsed() ? "mixed" : "live";
    }

    private static String buildScholarshipQuery(String query, List<String> facts) {
        String safe = query == null || query.isBlank() ? "장학금 성적 기준 취득학점 중복수혜" : query.trim();
        if (facts.isEmpty()) {
            return safe;
        }
        return safe + " " + String.join(" ", facts);
    }

    private static int safeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record Score(int value, List<String> matchedTerms) {
    }

    private record Candidate(
            AcademicPolicySource source,
            int chunkIndex,
            String chunkText,
            int lexScore,
            List<String> matchedTerms) {
    }
}
