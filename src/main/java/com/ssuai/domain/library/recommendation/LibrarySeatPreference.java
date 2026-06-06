package com.ssuai.domain.library.recommendation;

public record LibrarySeatPreference(
        Boolean window,
        Boolean outlet,
        Boolean standing,
        Boolean edge,
        Boolean quiet,
        Boolean nearEntrance
) {

    public boolean hasAnyPreference() {
        return window != null
                || outlet != null
                || standing != null
                || edge != null
                || quiet != null
                || nearEntrance != null;
    }
}
