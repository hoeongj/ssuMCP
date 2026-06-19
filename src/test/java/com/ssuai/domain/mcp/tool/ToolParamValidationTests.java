package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Boundary-validation helpers for tool params (⑦ misc robustness, ADR 0046).
 */
class ToolParamValidationTests {

    @Test
    void clampWeekOffset_nullBecomesZero_andClampsToRange() {
        assertThat(MealMcpTools.clampWeekOffset(null)).isZero();
        assertThat(MealMcpTools.clampWeekOffset(0)).isZero();
        assertThat(MealMcpTools.clampWeekOffset(3)).isEqualTo(3);
        assertThat(MealMcpTools.clampWeekOffset(-3)).isEqualTo(-3);
        assertThat(MealMcpTools.clampWeekOffset(999999)).isEqualTo(8);
        assertThat(MealMcpTools.clampWeekOffset(-999999)).isEqualTo(-8);
    }

    @Test
    void parseSeatId_acceptsPositive_rejectsNonNumericAndNonPositive() {
        assertThat(LibraryWaitMcpTool.parseSeatId(null)).isNull();
        assertThat(LibraryWaitMcpTool.parseSeatId("  ")).isNull();
        assertThat(LibraryWaitMcpTool.parseSeatId("42")).isEqualTo(42L);
        assertThatThrownBy(() -> LibraryWaitMcpTool.parseSeatId("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
        assertThatThrownBy(() -> LibraryWaitMcpTool.parseSeatId("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> LibraryWaitMcpTool.parseSeatId("-5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
