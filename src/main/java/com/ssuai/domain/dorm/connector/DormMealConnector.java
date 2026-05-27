package com.ssuai.domain.dorm.connector;

import com.ssuai.domain.meal.dto.WeeklyMealResponse;

public interface DormMealConnector {

    WeeklyMealResponse fetchThisWeekMeal();
}
