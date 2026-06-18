package com.ssuai.domain.lms.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.ssuai.domain.lms.dto.LmsTermItem;

/**
 * Resolves "the term the student is currently attending" from a list of
 * Canvas enrollment terms.
 *
 * <p>Canvas's {@code default} flag on a term can mean "the term currently
 * open for registration" rather than "the term with active classes right
 * now" (e.g. summer-term registration opens while spring classes are still
 * running) — confirmed by a real user report. Prefer date-overlap: pick the
 * term whose [startAt, endAt] window contains now. Fall back to the
 * Canvas-provided default flag, then the first term, if no window matches
 * (e.g. inter-term gap, or terms with null/unparsable dates).
 */
public final class LmsTermResolver {

    private LmsTermResolver() {
    }

    public static long resolveCurrentTermId(List<LmsTermItem> terms) {
        return resolveCurrentTermId(terms, Instant.now());
    }

    /**
     * Returns a copy of {@code terms} in which exactly one term — the one chosen by
     * {@link #resolveCurrentTermId(List)} — has {@code defaultTerm=true} and all others false.
     *
     * <p>Canvas can mark several terms {@code default} at once (e.g. summer-registration opens
     * while spring classes are still running), so the raw flag is ambiguous when surfaced to a
     * client (external review — multiple {@code defaultTerm:true}). This collapses it to the
     * single term the server actually treats as current, keeping the exposed list consistent
     * with the term used by assignments/export. Null or empty input is returned unchanged.
     */
    public static List<LmsTermItem> withResolvedDefault(List<LmsTermItem> terms) {
        return withResolvedDefault(terms, Instant.now());
    }

    static List<LmsTermItem> withResolvedDefault(List<LmsTermItem> terms, Instant now) {
        if (terms == null || terms.isEmpty()) {
            return terms;
        }
        long currentId = resolveCurrentTermId(terms, now);
        List<LmsTermItem> normalized = new ArrayList<>(terms.size());
        for (LmsTermItem term : terms) {
            boolean shouldBeDefault = term.id() == currentId;
            normalized.add(shouldBeDefault == term.defaultTerm()
                    ? term
                    : new LmsTermItem(term.id(), term.name(), term.startAt(), term.endAt(), shouldBeDefault));
        }
        return List.copyOf(normalized);
    }

    static long resolveCurrentTermId(List<LmsTermItem> terms, Instant now) {
        if (terms == null || terms.isEmpty()) {
            throw new IllegalArgumentException("terms must not be empty");
        }
        for (LmsTermItem term : terms) {
            if (isWithin(term, now)) {
                return term.id();
            }
        }
        return terms.stream()
                .filter(LmsTermItem::defaultTerm)
                .mapToLong(LmsTermItem::id)
                .findFirst()
                .orElse(terms.get(0).id());
    }

    private static boolean isWithin(LmsTermItem term, Instant now) {
        Instant start = parseQuietly(term.startAt());
        Instant end = parseQuietly(term.endAt());
        if (start == null || end == null) {
            return false;
        }
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private static Instant parseQuietly(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(isoInstant);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
