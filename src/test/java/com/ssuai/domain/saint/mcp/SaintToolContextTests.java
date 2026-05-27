package com.ssuai.domain.saint.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SaintToolContextTests {

    @Test
    void currentStudentIdIsNullOutsideScope() {
        assertThat(SaintToolContext.currentStudentId()).isNull();
    }

    @Test
    void withStudentIdBindsCurrentThreadAndRestoresOnClose() {
        try (SaintToolContext.Scope ignored = SaintToolContext.withStudentId("20221528")) {
            assertThat(SaintToolContext.currentStudentId()).isEqualTo("20221528");
        }

        assertThat(SaintToolContext.currentStudentId()).isNull();
    }

    @Test
    void nestedScopesRestorePreviousBindingOnClose() {
        try (SaintToolContext.Scope outer = SaintToolContext.withStudentId("outer-id")) {
            assertThat(SaintToolContext.currentStudentId()).isEqualTo("outer-id");

            try (SaintToolContext.Scope inner = SaintToolContext.withStudentId("inner-id")) {
                assertThat(SaintToolContext.currentStudentId()).isEqualTo("inner-id");
            }

            assertThat(SaintToolContext.currentStudentId()).isEqualTo("outer-id");
        }

        assertThat(SaintToolContext.currentStudentId()).isNull();
    }

    @Test
    void blankStudentIdIsStoredAsIsSoToolCanProduceItsOwnError() {
        try (SaintToolContext.Scope ignored = SaintToolContext.withStudentId("")) {
            assertThat(SaintToolContext.currentStudentId()).isEmpty();
        }

        try (SaintToolContext.Scope ignored = SaintToolContext.withStudentId(null)) {
            assertThat(SaintToolContext.currentStudentId()).isNull();
        }
    }
}
