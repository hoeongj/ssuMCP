package com.ssuai.domain.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.domain.library.service.LibraryBookService;
import com.ssuai.global.exception.ConnectorException;

@Component
public class LibraryBookMcpTool {

    private final LibraryBookService libraryBookService;

    public LibraryBookMcpTool(LibraryBookService libraryBookService) {
        this.libraryBookService = libraryBookService;
    }

    @Tool(
            name = "search_library_book",
            description = "숭실대학교 중앙도서관 소장 도서를 키워드로 검색합니다. "
                    + "응답에는 도서별 제목·저자·청구기호·소장 위치·대출 가능 여부가 포함됩니다. "
                    + "검색어는 제목·저자·출판 정보에 부분 일치합니다. "
                    + "이 도구는 읽기 전용이며, 도서 대출/예약 액션은 별도 도구로 분리되어 있습니다."
    )
    public LibraryBookSearchResponse searchLibraryBook(
            @ToolParam(description = "검색어 (제목/저자/출판 키워드, 1~64자)")
            String query,
            @ToolParam(description = "페이지 (0-based, 기본 0)", required = false)
            Integer page,
            @ToolParam(description = "페이지당 결과 수 (1~20, 기본 10)", required = false)
            Integer size
    ) {
        try {
            return libraryBookService.search(query, page, size);
        } catch (ConnectorException exception) {
            throw new IllegalStateException(
                    ConnectorErrorMessages.forResource("도서관 도서 검색", exception), exception);
        }
    }
}
