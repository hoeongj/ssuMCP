package com.ssuai.domain.meal.dto;

public enum MealClosureType {
    /** Official confirmed closure (공휴일, 휴무, 방학, 운영 안 함 등). */
    CLOSED,
    /** No menu data available yet — typically future dates or unpublished menus. */
    NO_MENU
}
