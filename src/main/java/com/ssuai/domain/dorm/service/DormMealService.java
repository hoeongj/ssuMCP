package com.ssuai.domain.dorm.service;

import org.springframework.stereotype.Service;

import com.ssuai.domain.dorm.connector.DormMealConnector;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;

@Service
public class DormMealService {

    private final DormMealConnector dormMealConnector;

    public DormMealService(DormMealConnector dormMealConnector) {
        this.dormMealConnector = dormMealConnector;
    }

    public WeeklyMealResponse getThisWeekMeal() {
        return dormMealConnector.fetchThisWeekMeal();
    }
}
