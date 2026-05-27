package com.ssuai.domain.library.connector;

import com.ssuai.domain.library.dto.LibraryBookSearchResponse;

public interface LibraryBookConnector {

    LibraryBookSearchResponse search(String query, int page, int size);
}
