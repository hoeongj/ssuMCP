package com.ssuai.domain.library.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LibraryBookSearchResponse(
        int total,
        int page,
        int size,
        List<LibraryBook> items
) {

    public LibraryBookSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 0) {
            throw new IllegalArgumentException("page cannot be negative");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size cannot be negative");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total cannot be negative");
        }
    }

    @JsonProperty("totalPages")
    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / (double) size);
    }

    @JsonProperty("hasNext")
    public boolean hasNext() {
        return size > 0 && (long) (page + 1) * (long) size < (long) total;
    }
}
