package com.ssuai.domain.mcp.tool;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.meal.dto.MealResponse;
import com.ssuai.domain.meal.dto.MealRestaurant;
import com.ssuai.domain.meal.service.MealService;
import com.ssuai.global.exception.ConnectorException;

@Component
public class MealMcpTools {

    private static final int MAX_ERROR_VALUE_LENGTH = 64;
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final String RESTAURANT_PARAM_DESCRIPTION =
            "선택사항. 특정 식당만 조회하고 싶을 때 식당 이름을 한국어로 전달합니다. "
                    + "가능한 값: 학생식당, 숭실도담식당, 스낵코너, 푸드코트, THE KITCHEN, FACULTY LOUNGE. "
                    + "비워두면 전체 식당의 메뉴를 함께 반환합니다.";
    private static final Map<String, MealRestaurant> RESTAURANT_ALIASES = Map.ofEntries(
            Map.entry("학생식당", MealRestaurant.STUDENT),
            Map.entry("student", MealRestaurant.STUDENT),
            Map.entry("숭실도담식당", MealRestaurant.DODAM),
            Map.entry("도담식당", MealRestaurant.DODAM),
            Map.entry("도담", MealRestaurant.DODAM),
            Map.entry("dodam", MealRestaurant.DODAM),
            Map.entry("스낵코너", MealRestaurant.SNACK),
            Map.entry("스낵", MealRestaurant.SNACK),
            Map.entry("snack", MealRestaurant.SNACK),
            Map.entry("푸드코트", MealRestaurant.FOOD_COURT),
            Map.entry("food court", MealRestaurant.FOOD_COURT),
            Map.entry("foodcourt", MealRestaurant.FOOD_COURT),
            Map.entry("the kitchen", MealRestaurant.THE_KITCHEN),
            Map.entry("kitchen", MealRestaurant.THE_KITCHEN),
            Map.entry("키친", MealRestaurant.THE_KITCHEN),
            Map.entry("faculty lounge", MealRestaurant.FACULTY_LOUNGE),
            Map.entry("faculty", MealRestaurant.FACULTY_LOUNGE),
            Map.entry("교직원식당", MealRestaurant.FACULTY_LOUNGE),
            Map.entry("교직원", MealRestaurant.FACULTY_LOUNGE)
    );

    private final MealService mealService;

    public MealMcpTools(MealService mealService) {
        this.mealService = mealService;
    }

    @Tool(
            name = "get_today_meal",
            description = "오늘 숭실대학교 학생식당의 메뉴를 조회합니다. restaurant 인자를 비워두면 학생식당, 숭실도담식당, FACULTY LOUNGE 등 캠퍼스 내 모든 식당의 코너별 메뉴와 휴무 정보를 함께 반환합니다. restaurant 인자를 지정하면 해당 식당만 조회합니다."
    )
    public MealResponse getTodayMeal(
            @ToolParam(description = RESTAURANT_PARAM_DESCRIPTION, required = false)
            String restaurant
    ) {
        LocalDate today = LocalDate.now(SEOUL_ZONE);
        return getMealInternal(today, restaurant);
    }

    @Tool(
            name = "get_meal_by_date",
            description = "지정한 날짜(yyyy-MM-dd)의 숭실대학교 학생식당 메뉴를 조회합니다. restaurant 인자를 비워두면 캠퍼스 내 모든 식당의 코너별 메뉴와 휴무 정보를 함께 반환합니다. restaurant 인자를 지정하면 해당 식당만 조회합니다."
    )
    public MealResponse getMealByDate(
            @ToolParam(description = "조회할 날짜. 반드시 ISO 형식 yyyy-MM-dd 의 문자열 (예: 2026-05-07). 빈 값/다른 형식이면 에러.")
            String date,
            @ToolParam(description = RESTAURANT_PARAM_DESCRIPTION, required = false)
            String restaurant
    ) {
        if (date == null || date.isBlank()) {
            throw new IllegalArgumentException(
                    "date: yyyy-MM-dd 형식의 날짜 문자열이 필요합니다. 예: 2026-05-07. 받은 값: '"
                            + displayValue(date) + "'.");
        }

        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "date: yyyy-MM-dd 형식이어야 합니다. 예: 2026-05-07. 받은 값: '"
                            + displayValue(date) + "'.",
                    exception);
        }

        return getMealInternal(parsed, restaurant);
    }

    private MealResponse getMealInternal(LocalDate date, String restaurantArg) {
        Optional<MealRestaurant> targetRestaurant = resolveRestaurant(restaurantArg);
        try {
            if (targetRestaurant.isPresent()) {
                return mealService.getMealForRestaurant(date, targetRestaurant.get());
            }
            return mealService.getMeal(date);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(ConnectorErrorMessages.forResource("학식", exception), exception);
        }
    }

    private static Optional<MealRestaurant> resolveRestaurant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        MealRestaurant exact = RESTAURANT_ALIASES.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (MealRestaurant restaurant : MealRestaurant.values()) {
            if (normalized.contains(restaurant.displayName().toLowerCase(Locale.ROOT))) {
                return Optional.of(restaurant);
            }
        }
        throw new IllegalArgumentException(
                "restaurant: 지원하지 않는 식당입니다. 가능한 값: 학생식당, 숭실도담식당, 스낵코너, 푸드코트, THE KITCHEN, FACULTY LOUNGE. 받은 값: '"
                        + displayValue(value) + "'.");
    }

    private static String displayValue(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= MAX_ERROR_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_VALUE_LENGTH) + "...";
    }
}
