package com.ssuai.domain.meal.dto;

public enum MealRestaurant {

    STUDENT("1", "학생식당"),
    DODAM("2", "숭실도담식당"),
    SNACK("4", "스낵코너"),
    FOOD_COURT("5", "푸드코트"),
    THE_KITCHEN("6", "THE KITCHEN"),
    FACULTY_LOUNGE("7", "FACULTY LOUNGE");

    private final String code;
    private final String displayName;

    MealRestaurant(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }
}
