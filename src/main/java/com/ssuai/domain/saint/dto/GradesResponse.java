package com.ssuai.domain.saint.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cumulative grades payload returned by the {@code GET /api/saint/grades}
 * endpoint and the {@code get_my_grades} MCP tool (Task 16 PR 16c).
 *
 * <p>{@code history} is the per-term GPA list parsed from ZCMB3W0017's
 * 상단 학기별 성적 표 — ordered as the page renders it (most recent
 * term first). {@code academicRecord} and {@code certificate} are the two
 * cumulative summary blocks; both are always present, even when 학적부
 * and 증명용 happen to agree numerically.
 *
 * <p>{@code detailsByTerm} maps each term's {@link TermGpa#termKey()} to
 * its per-course rows, populated by the 이전학기 button-press iterate.
 * Insertion-ordered (LinkedHashMap-backed) so callers iterating it see
 * the same order as {@code history}. Terms whose detail rows came back
 * empty (the P/F-only case) simply don't have a key in the map.
 *
 * <p>The frontend dashboard renders {@code history} + summaries; the
 * chat path is constrained per spec §6 #6 to surface only a tool-level
 * count + a deep link — never the {@code detailsByTerm} contents.
 */
public record GradesResponse(
        List<TermGpa> history,
        GpaSummary academicRecord,
        GpaSummary certificate,
        Map<String, List<CourseGrade>> detailsByTerm
) {

    public GradesResponse {
        history = history == null ? List.of() : List.copyOf(history);
        detailsByTerm = detailsByTerm == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(detailsByTerm));
    }
}
