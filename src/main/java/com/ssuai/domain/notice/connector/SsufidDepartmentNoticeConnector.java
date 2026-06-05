package com.ssuai.domain.notice.connector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.notice.dto.Notice;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;

@Component
@ConditionalOnProperty(name = "ssuai.connector.department-notice", havingValue = "real")
public class SsufidDepartmentNoticeConnector implements DepartmentNoticeConnector {

    private static final Logger log = LoggerFactory.getLogger(SsufidDepartmentNoticeConnector.class);

    private static final Map<String, List<String>> DEPT_SLUGS = Map.ofEntries(
        Map.entry("컴퓨터학부", List.of("cse.ssu.ac.kr/bachelor", "cse.ssu.ac.kr/graduate", "cse.ssu.ac.kr/employment")),
        Map.entry("소프트웨어학부", List.of("sw.ssu.ac.kr/bachelor", "sw.ssu.ac.kr/graduate")),
        Map.entry("경영학부", List.of("biz.ssu.ac.kr")),
        Map.entry("경제학과", List.of("eco.ssu.ac.kr")),
        Map.entry("회계학부", List.of("accounting.ssu.ac.kr")),
        Map.entry("금융학부", List.of("finance.ssu.ac.kr")),
        Map.entry("IT경영학과", List.of("itrans.ssu.ac.kr")),
        Map.entry("경영대학원", List.of("mediamba.ssu.ac.kr")),
        Map.entry("전기공학부", List.of("ee.ssu.ac.kr")),
        Map.entry("전자정보공학부", List.of("infocom.ssu.ac.kr")),
        Map.entry("화학공학과", List.of("chemeng.ssu.ac.kr")),
        Map.entry("유기신소재파이버공학과", List.of("materials.ssu.ac.kr")),
        Map.entry("기계공학부", List.of("ensb.ssu.ac.kr")),
        Map.entry("산업정보시스템공학부", List.of("iise.ssu.ac.kr")),
        Map.entry("글로벌통상학과", List.of("gtrade.ssu.ac.kr")),
        Map.entry("법학과", List.of("law.ssu.ac.kr")),
        Map.entry("법학전문대학원", List.of("lawyer.ssu.ac.kr")),
        Map.entry("정치외교학과", List.of("politics.ssu.ac.kr")),
        Map.entry("행정학부", List.of("pubad.ssu.ac.kr")),
        Map.entry("미디어학부", List.of("media.ssu.ac.kr")),
        Map.entry("사회복지학부", List.of("sls.ssu.ac.kr")),
        Map.entry("수학과", List.of("math.ssu.ac.kr")),
        Map.entry("물리학과", List.of("physics.ssu.ac.kr")),
        Map.entry("화학과", List.of("chem.ssu.ac.kr")),
        Map.entry("바이오정보시스템학과", List.of("bioinfo.ssu.ac.kr")),
        Map.entry("국어국문학과", List.of("korlan.ssu.ac.kr")),
        Map.entry("영어영문학과", List.of("englan.ssu.ac.kr")),
        Map.entry("독어독문학과", List.of("gerlan.ssu.ac.kr")),
        Map.entry("프랑스어문학과", List.of("france.ssu.ac.kr")),
        Map.entry("일어일문학과", List.of("japanstu.ssu.ac.kr")),
        Map.entry("중어중문학과", List.of("chilan.ssu.ac.kr")),
        Map.entry("철학과", List.of("philo.ssu.ac.kr")),
        Map.entry("사학과", List.of("history.ssu.ac.kr")),
        Map.entry("기독교학과", List.of("path.ssu.ac.kr")),
        Map.entry("스포츠학부", List.of("sports.ssu.ac.kr")),
        Map.entry("영화예술전공", List.of("ssfilm.ssu.ac.kr")),
        Map.entry("문예창작학과", List.of("actx.ssu.ac.kr")),
        Map.entry("벤처중소기업학과", List.of("inso.ssu.ac.kr")),
        Map.entry("창업지원단", List.of("startup.ssu.ac.kr")),
        Map.entry("학생처", List.of("stu.ssu.ac.kr")),
        Map.entry("기숙사", List.of("ssudorm.ssu.ac.kr")),
        Map.entry("오아시스", List.of("oasis.ssu.ac.kr")),
        Map.entry("SOAR", List.of("soar.ssu.ac.kr")),
        Map.entry("미래교육원", List.of("lifelongedu.ssu.ac.kr")),
        Map.entry("교무학사지원팀", List.of("study.ssu.ac.kr")),
        Map.entry("베어드학부대학", List.of("docs.ssu.ac.kr")),
        Map.entry("글로벌미디어학부", List.of("masscom.ssu.ac.kr")),
        Map.entry("중등교육과정", List.of("sec.ssu.ac.kr")),
        Map.entry("AI융합학부", List.of("mysoongsil.ssu.ac.kr"))
    );

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean delayBeforeRequest;

    @Autowired
    public SsufidDepartmentNoticeConnector(
            @Value("${ssuai.ssufid.base-url:https://ssufid.yourssu.com}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this(baseUrl, objectMapper, true);
    }

    SsufidDepartmentNoticeConnector(String baseUrl, ObjectMapper objectMapper, boolean delayBeforeRequest) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.delayBeforeRequest = delayBeforeRequest;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public NoticeListResponse fetchByDepartment(String departmentName, int page) {
        List<String> slugs = DEPT_SLUGS.get(departmentName);
        if (slugs == null || slugs.isEmpty()) {
            log.info("No ssufid slugs mapped for department: {}", departmentName);
            return new NoticeListResponse(List.of(), page, 1);
        }

        List<SsufidItem> allItems = new ArrayList<>();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        for (String slug : slugs) {
            String url = normalizedBase + slug + "/data.json";
            try {
                String json = fetchJson(url);
                SsufidResponse response = objectMapper.readValue(json, SsufidResponse.class);
                if (response != null && response.items() != null) {
                    allItems.addAll(response.items());
                }
            } catch (ConnectorTimeoutException | ConnectorUnavailableException e) {
                log.error("Failed to fetch notices from slug: {}, error: {}", slug, e.getMessage());
                throw alert(e);
            } catch (Exception e) {
                log.error("Failed to parse notices from slug: {}, error: {}", slug, e.getMessage());
                ConnectorUnavailableException mapped = new ConnectorUnavailableException(e);
                throw alert(mapped);
            }
        }

        // Sort by created_at DESC
        allItems.sort((a, b) -> {
            try {
                ZonedDateTime adt = ZonedDateTime.parse(a.createdAt());
                ZonedDateTime bdt = ZonedDateTime.parse(b.createdAt());
                return bdt.compareTo(adt);
            } catch (Exception e) {
                String ac = a.createdAt() == null ? "" : a.createdAt();
                String bc = b.createdAt() == null ? "" : b.createdAt();
                return bc.compareTo(ac);
            }
        });

        // Pagination: page size = 10, 1-indexed
        int pageSize = 10;
        int totalItems = allItems.size();
        int totalPages = (totalItems + pageSize - 1) / pageSize;
        if (totalPages == 0) {
            totalPages = 1;
        }

        if (page < 1 || page > totalPages) {
            return new NoticeListResponse(List.of(), page, totalPages);
        }

        int startIdx = (page - 1) * pageSize;
        int endIdx = Math.min(startIdx + pageSize, totalItems);
        List<SsufidItem> pageItems = allItems.subList(startIdx, endIdx);

        // Map to Notice DTOs
        List<Notice> notices = pageItems.stream()
                .map(item -> {
                    String dateStr = "";
                    if (item.createdAt() != null) {
                        try {
                            ZonedDateTime zdt = ZonedDateTime.parse(item.createdAt());
                            dateStr = zdt.format(DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneId.of("Asia/Seoul")));
                        } catch (Exception e) {
                            dateStr = item.createdAt();
                        }
                    }
                    String dept = item.author() != null ? item.author() : departmentName;
                    String cat = (item.category() != null && !item.category().isEmpty()) ? item.category().getFirst() : "";
                    return new Notice(
                            item.title(),
                            item.url(),
                            dateStr,
                            "", // status
                            dept,
                            cat
                    );
                })
                .toList();

        return new NoticeListResponse(notices, page, totalPages);
    }

    @Override
    public List<String> listDepartments() {
        List<String> list = new ArrayList<>(DEPT_SLUGS.keySet());
        Collections.sort(list);
        return list;
    }

    private String fetchJson(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            delayBeforeRequest();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("API returned non-200 status code: {} for URL: {}", response.statusCode(), url);
                throw new ConnectorUnavailableException();
            }
            return response.body();
        } catch (HttpTimeoutException e) {
            throw new ConnectorTimeoutException(e);
        } catch (IOException e) {
            if (e.getCause() instanceof java.net.SocketTimeoutException) {
                throw new ConnectorTimeoutException(e);
            }
            throw new ConnectorUnavailableException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorUnavailableException(e);
        }
    }

    private static ConnectorException alert(ConnectorException exception) {
        return exception;
    }

    private void delayBeforeRequest() {
        if (!delayBeforeRequest) {
            return;
        }
        try {
            long delayMs = ThreadLocalRandom.current().nextLong(300, 1200);
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SsufidResponse(
            String title,
            List<SsufidItem> items
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SsufidItem(
            String id,
            String url,
            String author,
            String title,
            String description,
            List<String> category,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt
    ) {}
}
