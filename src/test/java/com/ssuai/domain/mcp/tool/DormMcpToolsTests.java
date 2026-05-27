package com.ssuai.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.dorm.service.DormMealService;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorParseException;

class DormMcpToolsTests {

    private final DormMealService dormMealService = mock(DormMealService.class);
    private final DormMcpTools tools = new DormMcpTools(dormMealService);

    @Test
    void getDormWeeklyMealDelegatesToService() {
        WeeklyMealResponse expected = new WeeklyMealResponse(
                LocalDate.of(2026, 5, 4),
                LocalDate.of(2026, 5, 10),
                List.of());
        when(dormMealService.getThisWeekMeal()).thenReturn(expected);

        WeeklyMealResponse response = tools.getDormWeeklyMeal();

        assertThat(response).isSameAs(expected);
        verify(dormMealService).getThisWeekMeal();
    }

    @Test
    void getDormWeeklyMealWrapsConnectorParseWithFriendlyMessage() {
        ConnectorParseException exception = new ConnectorParseException();
        when(dormMealService.getThisWeekMeal()).thenThrow(exception);

        assertThatThrownBy(tools::getDormWeeklyMeal)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기숙사 식단")
                .hasMessageContaining("응답 구조")
                .satisfies(thrown -> assertThat(thrown.getCause()).isSameAs(exception));
    }
}
