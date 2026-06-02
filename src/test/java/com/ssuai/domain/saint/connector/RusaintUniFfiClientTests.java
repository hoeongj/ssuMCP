package com.ssuai.domain.saint.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class RusaintUniFfiClientTests {

    private final RusaintUniFfiClient client = new RusaintUniFfiClient();

    @Test
    void mapsEightAmToDisplayOnlyPeriodZeroWithoutShiftingExistingPeriods() throws Exception {
        assertThat(periodFromTimeRange("08:00-08:50")).isZero();
        assertThat(periodFromTimeRange("1 교시\n(08:00~08:50)")).isZero();

        assertThat(periodFromTimeRange("09:00-10:15")).isEqualTo(1);
        assertThat(periodFromTimeRange("09:00~10:15")).isEqualTo(1);
    }

    @Test
    void returnsZeroWhenNoStartTimeCanBeFound() throws Exception {
        assertThat(periodFromTimeRange("")).isZero();
        assertThat(periodFromTimeRange("시간 미정")).isZero();
    }

    private int periodFromTimeRange(String timeRange) throws Exception {
        Method method = RusaintUniFfiClient.class.getDeclaredMethod("periodFromTimeRange", String.class);
        method.setAccessible(true);
        return (int) method.invoke(client, timeRange);
    }
}
