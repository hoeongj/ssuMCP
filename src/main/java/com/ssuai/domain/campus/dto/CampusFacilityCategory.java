package com.ssuai.domain.campus.dto;

public enum CampusFacilityCategory {

    CAFETERIA("식당"),
    CONVENIENCE_STORE("편의점"),
    CAFE("카페"),
    BOOKSTORE_STATIONERY("서점/문구점"),
    SNACK("스낵"),
    BAKERY("베이커리"),
    GIFT_SHOP("기념품샵"),
    PRINT_SHOP("복사/출력");

    private final String label;

    CampusFacilityCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
