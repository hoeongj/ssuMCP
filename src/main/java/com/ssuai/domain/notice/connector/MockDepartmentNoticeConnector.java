package com.ssuai.domain.notice.connector;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeListResponse;

@Component
@ConditionalOnProperty(name = "ssuai.connector.department-notice", havingValue = "mock", matchIfMissing = true)
public class MockDepartmentNoticeConnector implements DepartmentNoticeConnector {

    @Override
    public NoticeListResponse fetchByDepartment(String departmentName, int page) {
        if ("장학팀".equals(departmentName)) {
            return new NoticeListResponse(List.of(
                    new Notice("장학금 신청 안내", "http://link", "2026.05.23", "", "장학팀", "장학")
            ), page, 1);
        }
        return new NoticeListResponse(List.of(), page, 1);
    }

    @Override
    public List<String> listDepartments() {
        return List.of("컴퓨터학부", "소프트웨어학부");
    }
}
