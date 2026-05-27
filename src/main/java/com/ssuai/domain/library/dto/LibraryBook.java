package com.ssuai.domain.library.dto;

public record LibraryBook(
        long id,
        String title,
        String author,
        String publication,
        String isbn,
        String thumbnailUrl,
        String callNumber,
        String location,
        BookStatus status
) {

    public LibraryBook {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title cannot be blank");
        }
        if (status == null) {
            status = BookStatus.UNKNOWN;
        }
    }
}
