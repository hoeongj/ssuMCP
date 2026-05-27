package com.ssuai.domain.notice.connector;

import com.ssuai.domain.notice.dto.NoticeListResponse;
import java.util.List;

public interface DepartmentNoticeConnector {
    /** 한국어 학과명 → 해당 학과 공지 목록. 없는 학과면 빈 목록 반환. */
    NoticeListResponse fetchByDepartment(String departmentName, int page);
    /** 지원하는 학과 이름 목록 반환 */
    List<String> listDepartments();
}
