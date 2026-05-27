package com.ssuai.domain.notice.connector;

import com.ssuai.domain.notice.dto.NoticeCategoriesResponse;
import com.ssuai.domain.notice.dto.NoticeDetailResponse;
import com.ssuai.domain.notice.dto.NoticeListResponse;

public interface NoticeConnector {

    NoticeListResponse fetchNotices(String category, int page);

    NoticeListResponse searchNotices(String keyword, String category, int page);

    NoticeCategoriesResponse fetchCategories();

    NoticeDetailResponse fetchDetail(String url);
}
