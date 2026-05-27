package com.ssuai.domain.dorm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.dorm.connector.DormMealConnector;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorUnavailableException;

class DormMealServiceTests {

    private final DormMealConnector connector = mock(DormMealConnector.class);
    private final DormMealService service = new DormMealService(connector);

    @Test
    void getThisWeekMealDelegatesToConnector() {
        WeeklyMealResponse expected = new WeeklyMealResponse(
                LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 10), List.of());
        when(connector.fetchThisWeekMeal()).thenReturn(expected);

        assertThat(service.getThisWeekMeal()).isSameAs(expected);
        verify(connector).fetchThisWeekMeal();
    }

    @Test
    void getThisWeekMealPropagatesConnectorException() {
        when(connector.fetchThisWeekMeal())
                .thenThrow(new ConnectorUnavailableException(new RuntimeException("503")));

        assertThatThrownBy(service::getThisWeekMeal)
                .isInstanceOf(ConnectorUnavailableException.class);
    }
}
