package com.ssuai.domain.saint.connector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for RusaintUniFfiClient.isChapelCourse().
 *
 * Background: The chapel course name varies by academic year — "CHAPEL" (2022 cohort)
 * vs "비전채플" (2025 cohort) — but the course code "21501015" is stable across all
 * naming changes (verified via get_my_grades, 2026-06-20). A predicate that relied
 * solely on the Korean name "채플" would silently miss the English-named rows and
 * return 2 instead of the correct 4 (a 4th deploy failure). These tests guard that
 * exact failure mode with no live rusaint session required.
 */
class RusaintUniFfiClientChapelMatchTest {

    @Test
    fun `CHAPEL (English, 2022 cohort) is recognised as chapel`() {
        // This is the case that would have caused a 4th silent failure:
        // className = "CHAPEL" does NOT contain "채플", so the old predicate returned false.
        assertThat(RusaintUniFfiClient.isChapelCourse("21501015", "CHAPEL")).isTrue()
    }

    @Test
    fun `비전채플 (Korean, 2025 cohort) is recognised as chapel`() {
        assertThat(RusaintUniFfiClient.isChapelCourse("21501015", "비전채플")).isTrue()
    }

    @Test
    fun `course code alone is sufficient — name need not match`() {
        // If the name changes again but the code stays, we still match.
        assertThat(RusaintUniFfiClient.isChapelCourse("21501015", "완전히다른이름")).isTrue()
    }

    @Test
    fun `unrelated course is not recognised as chapel`() {
        assertThat(RusaintUniFfiClient.isChapelCourse("00000000", "자료구조")).isFalse()
    }
}
