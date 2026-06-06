package com.ssuai.domain.library.recommendation;

import java.util.ArrayList;
import java.util.List;

public record LibrarySeatAttributes(
        boolean window,
        boolean outlet,
        boolean standing,
        boolean edge,
        boolean quiet,
        boolean nearEntrance
) {

    public List<String> tags() {
        List<String> tags = new ArrayList<>();
        if (window) {
            tags.add("window");
        }
        if (outlet) {
            tags.add("outlet");
        }
        if (standing) {
            tags.add("standing");
        }
        if (edge) {
            tags.add("edge");
        }
        if (quiet) {
            tags.add("quiet");
        }
        if (nearEntrance) {
            tags.add("nearEntrance");
        }
        return List.copyOf(tags);
    }
}
