package com.ssuai.domain.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.ssuai.domain.dorm.service.DormMealService;
import com.ssuai.domain.meal.dto.WeeklyMealResponse;
import com.ssuai.global.exception.ConnectorException;

@Component
public class DormMcpTools {

    private final DormMealService dormMealService;

    public DormMcpTools(DormMealService dormMealService) {
        this.dormMealService = dormMealService;
    }

    @Tool(
            name = "get_dorm_weekly_meal",
            description = "숭실대학교 레지던스홀(기숙사) 식당의 이번 주 주간 메뉴를 조회합니다. 7일치 조식/중식/석식과 휴무 정보를 함께 반환합니다."
    )
    public WeeklyMealResponse getDormWeeklyMeal() {
        try {
            return dormMealService.getThisWeekMeal();
        } catch (ConnectorException exception) {
            throw new IllegalStateException(ConnectorErrorMessages.forResource("기숙사 식단", exception), exception);
        }
    }
}
