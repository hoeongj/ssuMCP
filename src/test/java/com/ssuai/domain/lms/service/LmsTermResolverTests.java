package com.ssuai.domain.lms.service;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.lms.dto.LmsTermItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class LmsTermResolverTests {

    @Test
    void testResolveCurrentTermId_overlappingTermWins() {
        // Given
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        List<LmsTermItem> terms = List.of(
                new LmsTermItem(10L, "2026 Summer", "2026-06-20T00:00:00Z", "2026-08-20T00:00:00Z", true),
                new LmsTermItem(20L, "2026 Spring", "2026-03-01T00:00:00Z", "2026-06-15T00:00:00Z", false)
        );

        // When
        long resolved = LmsTermResolver.resolveCurrentTermId(terms, now);

        // Then
        assertThat(resolved).isEqualTo(20L); // Spring overlaps with April 15
    }

    @Test
    void testResolveCurrentTermId_fallsBackToDefaultTerm() {
        // Given
        Instant now = Instant.parse("2026-09-15T12:00:00Z"); // No overlap
        List<LmsTermItem> terms = List.of(
                new LmsTermItem(10L, "2026 Summer", "2026-06-20T00:00:00Z", "2026-08-20T00:00:00Z", false),
                new LmsTermItem(20L, "2026 Spring", "2026-03-01T00:00:00Z", "2026-06-15T00:00:00Z", true)
        );

        // When
        long resolved = LmsTermResolver.resolveCurrentTermId(terms, now);

        // Then
        assertThat(resolved).isEqualTo(20L); // Falls back to defaultTerm=true
    }

    @Test
    void testResolveCurrentTermId_fallsBackToFirstTerm() {
        // Given
        Instant now = Instant.parse("2026-09-15T12:00:00Z"); // No overlap, no defaultTerm
        List<LmsTermItem> terms = List.of(
                new LmsTermItem(10L, "2026 Summer", "2026-06-20T00:00:00Z", "2026-08-20T00:00:00Z", false),
                new LmsTermItem(20L, "2026 Spring", "2026-03-01T00:00:00Z", "2026-06-15T00:00:00Z", false)
        );

        // When
        long resolved = LmsTermResolver.resolveCurrentTermId(terms, now);

        // Then
        assertThat(resolved).isEqualTo(10L); // Falls back to first term in the list
    }

    @Test
    void testResolveCurrentTermId_nullOrBlankDatesDoNotThrow() {
        // Given
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        List<LmsTermItem> terms = List.of(
                new LmsTermItem(10L, "Term A", null, null, false),
                new LmsTermItem(20L, "Term B", "", "invalid-date", true)
        );

        // When
        long resolved = LmsTermResolver.resolveCurrentTermId(terms, now);

        // Then
        assertThat(resolved).isEqualTo(20L); // Falls back to defaultTerm=true because date parsing failed/skipped
    }

    @Test
    void testResolveCurrentTermId_emptyListThrows() {
        assertThatThrownBy(() -> LmsTermResolver.resolveCurrentTermId(List.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terms must not be empty");
    }

    @Test
    void testWithResolvedDefault_collapsesMultipleDefaultsToSingleOverlappingTerm() {
        // Given — Canvas marks all three terms default at once (the reported bug).
        Instant now = Instant.parse("2026-04-15T12:00:00Z");
        List<LmsTermItem> terms = List.of(
                new LmsTermItem(10L, "2026 Summer", "2026-06-20T00:00:00Z", "2026-08-20T00:00:00Z", true),
                new LmsTermItem(20L, "2026 Spring", "2026-03-01T00:00:00Z", "2026-06-15T00:00:00Z", true),
                new LmsTermItem(30L, "2025 Winter", "2025-12-01T00:00:00Z", "2026-02-20T00:00:00Z", true));

        // When
        List<LmsTermItem> resolved = LmsTermResolver.withResolvedDefault(terms, now);

        // Then — exactly one defaultTerm=true: the term whose window contains now (Spring).
        assertThat(resolved)
                .extracting(LmsTermItem::id, LmsTermItem::defaultTerm)
                .containsExactly(
                        tuple(10L, false),
                        tuple(20L, true),
                        tuple(30L, false));
    }

    @Test
    void testWithResolvedDefault_setsDefaultWhenCanvasMarkedNone() {
        // Given — no term overlaps now and none is flagged default → resolver falls back to first.
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        List<LmsTermItem> terms = List.of(
                new LmsTermItem(10L, "2026 Summer", "2026-06-20T00:00:00Z", "2026-08-20T00:00:00Z", false),
                new LmsTermItem(20L, "2026 Spring", "2026-03-01T00:00:00Z", "2026-06-15T00:00:00Z", false));

        // When
        List<LmsTermItem> resolved = LmsTermResolver.withResolvedDefault(terms, now);

        // Then — first term becomes the single default.
        assertThat(resolved)
                .extracting(LmsTermItem::id, LmsTermItem::defaultTerm)
                .containsExactly(tuple(10L, true), tuple(20L, false));
    }

    @Test
    void testWithResolvedDefault_returnsEmptyAndNullUnchanged() {
        assertThat(LmsTermResolver.withResolvedDefault(List.of(), Instant.now())).isEmpty();
        assertThat(LmsTermResolver.withResolvedDefault(null, Instant.now())).isNull();
    }
}
