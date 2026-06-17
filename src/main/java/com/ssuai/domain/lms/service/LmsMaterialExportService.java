package com.ssuai.domain.lms.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssuai.domain.action.ActionAudit;
import com.ssuai.domain.action.ActionService;
import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsCourseMaterials;
import com.ssuai.domain.lms.dto.LmsExportConfirmResponse;
import com.ssuai.domain.lms.dto.LmsExportExclusion;
import com.ssuai.domain.lms.dto.LmsExportPrepareResponse;
import com.ssuai.domain.lms.dto.LmsExportSelectionItem;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.dto.LmsMaterialGroup;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.dto.SelectionPayload;
import com.ssuai.domain.lms.export.LmsExportJob;
import com.ssuai.domain.lms.export.LmsExportJobRepository;
import com.ssuai.domain.lms.export.LmsExportProperties;
import com.ssuai.domain.lms.util.MaterialFileFilter;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class LmsMaterialExportService {

    private static final Logger log = LoggerFactory.getLogger(LmsMaterialExportService.class);

    private final LmsMaterialsConnector connector;
    private final LmsSessionStore sessionStore;
    private final LmsAssignmentsService assignmentsService;
    private final ActionService actionService;
    private final LmsExportJobRepository jobRepository;
    private final LmsExportProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public LmsMaterialExportService(LmsMaterialsConnector connector, LmsSessionStore sessionStore,
                                    LmsAssignmentsService assignmentsService, ActionService actionService,
                                    LmsExportJobRepository jobRepository, LmsExportProperties properties,
                                    ObjectMapper objectMapper) {
        this.connector = connector;
        this.sessionStore = sessionStore;
        this.assignmentsService = assignmentsService;
        this.actionService = actionService;
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LmsExportPrepareResponse prepare(String studentId, Long termId, List<String> contentIds) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        LmsCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(LmsSessionExpiredException::new);

        long resolvedTermId;
        if (termId != null) {
            resolvedTermId = termId;
        } else {
            List<LmsTermItem> terms = assignmentsService.fetchTerms(studentId);
            resolvedTermId = LmsTermResolver.resolveCurrentTermId(terms);
        }

        List<LmsCourse> courses = connector.fetchCourses(studentId, cookies, resolvedTermId);
        Map<String, LmsMaterial> allMaterialsMap = new HashMap<>();
        Map<Long, LmsCourse> courseMap = new HashMap<>();

        for (LmsCourse course : courses) {
            courseMap.put(course.id(), course);
            List<LmsMaterial> materials = connector.fetchMaterials(studentId, cookies, course);
            for (LmsMaterial material : materials) {
                if (material.contentId() != null) {
                    allMaterialsMap.put(material.contentId(), material);
                }
            }
        }

        List<LmsMaterial> whitelistedSelections = new ArrayList<>();
        List<LmsExportExclusion> excluded = new ArrayList<>();

        if (contentIds != null) {
            for (String contentId : contentIds) {
                LmsMaterial material = allMaterialsMap.get(contentId);
                if (material == null) {
                    excluded.add(new LmsExportExclusion(contentId, "", "파일을 찾을 수 없습니다."));
                } else if (!MaterialFileFilter.isIncluded(material)) {
                    excluded.add(new LmsExportExclusion(contentId, material.fileName(), "지원하지 않는 파일 형식 또는 비디오입니다."));
                } else {
                    whitelistedSelections.add(material);
                }
            }
        }

        return finalizeExport(studentId, courseMap, whitelistedSelections, excluded);
    }

    public LmsExportPrepareResponse exportAll(String studentId, Long termId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }
        LmsCookies cookies = sessionStore.cookies(studentId)
                .orElseThrow(LmsSessionExpiredException::new);

        // Resolve term — fetch terms to get term name for the response message
        long resolvedTermId;
        String termLabel;
        List<LmsTermItem> terms = assignmentsService.fetchTerms(studentId);
        if (termId != null) {
            resolvedTermId = termId;
            termLabel = terms.stream()
                    .filter(t -> t.id() == termId)
                    .map(LmsTermItem::name)
                    .findFirst()
                    .orElse("학기 #" + termId);
        } else {
            resolvedTermId = LmsTermResolver.resolveCurrentTermId(terms);
            termLabel = terms.stream()
                    .filter(t -> t.id() == resolvedTermId)
                    .map(LmsTermItem::name)
                    .findFirst()
                    .orElse("현재 학기");
        }

        // Gather all courses and all eligible materials
        List<LmsCourse> courses = connector.fetchCourses(studentId, cookies, resolvedTermId);
        Map<Long, LmsCourse> courseMap = new HashMap<>();
        List<LmsMaterial> whitelistedSelections = new ArrayList<>();

        for (LmsCourse course : courses) {
            courseMap.put(course.id(), course);
            List<LmsMaterial> materials = connector.fetchMaterials(studentId, cookies, course);
            for (LmsMaterial material : materials) {
                if (material.contentId() != null && MaterialFileFilter.isIncluded(material)) {
                    whitelistedSelections.add(material);
                }
            }
        }

        // Delegate limit/grouping/pending-action creation to shared helper
        LmsExportPrepareResponse response = finalizeExport(studentId, courseMap, whitelistedSelections, new ArrayList<>());

        // Prepend term label to the response message (reuse message field — no DTO schema change)
        String newMessage = "[" + termLabel + "] " + response.message();
        return new LmsExportPrepareResponse(
                response.courseCount(),
                response.fileCount(),
                response.totalBytes(),
                response.selected(),
                response.excluded(),
                newMessage);
    }

    private LmsExportPrepareResponse finalizeExport(
            String studentId,
            Map<Long, LmsCourse> courseMap,
            List<LmsMaterial> whitelistedSelections,
            List<LmsExportExclusion> seededExclusions) {
        List<LmsExportExclusion> excluded = new ArrayList<>(seededExclusions);

        // Sort whitelisted selections by size descending to prioritize largest files for limit check
        List<LmsMaterial> sortedSelections = whitelistedSelections.stream()
                .sorted(Comparator.comparing(LmsMaterial::sizeBytes, Comparator.nullsLast(Long::compareTo)).reversed())
                .toList();

        List<LmsMaterial> acceptedSelections = new ArrayList<>();
        int maxFiles = properties.getMaxFilesPerExport();
        long maxBytes = properties.getMaxBytesPerExport();

        int accumulatedCount = 0;
        long accumulatedBytes = 0;

        for (LmsMaterial material : sortedSelections) {
            long size = material.sizeBytes() != null ? material.sizeBytes() : 0;
            if (accumulatedCount + 1 <= maxFiles && accumulatedBytes + size <= maxBytes) {
                acceptedSelections.add(material);
                accumulatedCount++;
                accumulatedBytes += size;
            } else {
                excluded.add(new LmsExportExclusion(material.contentId(), material.fileName(), "한도 초과"));
            }
        }

        // Group accepted selections by course for response structure
        Map<Long, List<LmsMaterial>> groupedByCourse = acceptedSelections.stream()
                .collect(Collectors.groupingBy(LmsMaterial::courseId));

        List<LmsCourseMaterials> selectedCourseMaterials = new ArrayList<>();
        for (Map.Entry<Long, List<LmsMaterial>> entry : groupedByCourse.entrySet()) {
            LmsCourse course = courseMap.get(entry.getKey());
            if (course == null) continue;

            List<LmsMaterial> courseSelected = entry.getValue();
            Map<String, List<LmsMaterial>> groupedByExt = courseSelected.stream()
                    .collect(Collectors.groupingBy(m -> {
                        String ext = m.extension();
                        return ext == null ? "" : ext.toLowerCase().trim();
                    }));

            List<LmsMaterialGroup> groups = new ArrayList<>();
            long courseBytes = 0;

            for (Map.Entry<String, List<LmsMaterial>> extEntry : groupedByExt.entrySet()) {
                String ext = extEntry.getKey();
                List<LmsMaterial> extMaterials = extEntry.getValue().stream()
                        .sorted(Comparator.comparing(LmsMaterial::weekTitle, Comparator.nullsFirst(String::compareTo))
                                .thenComparing(LmsMaterial::title, Comparator.nullsFirst(String::compareTo)))
                        .toList();

                groups.add(new LmsMaterialGroup(ext, extMaterials.size(), extMaterials));
                for (LmsMaterial m : extMaterials) {
                    if (m.sizeBytes() != null) {
                        courseBytes += m.sizeBytes();
                    }
                }
            }

            groups.sort(Comparator.comparing(LmsMaterialGroup::extension));
            selectedCourseMaterials.add(new LmsCourseMaterials(course, groups, courseSelected.size(), courseBytes));
        }

        // Sort course materials by course ID
        selectedCourseMaterials.sort(Comparator.comparing(c -> c.course().id()));

        // Create pending action payload
        List<LmsExportSelectionItem> payloadItems = acceptedSelections.stream()
                .map(m -> new LmsExportSelectionItem(m.contentId(), m.courseId(), m.courseName(), m.fileName()))
                .toList();

        SelectionPayload payload = new SelectionPayload(payloadItems, accumulatedBytes);
        actionService.createPendingAction(studentId, "LMS_MATERIAL_EXPORT", payload);

        String message = String.format("내보내기 준비 완료. %d개 파일 (%,d bytes)이 내보내기 목록에 추가되었습니다.",
                accumulatedCount, accumulatedBytes);
        if (!excluded.isEmpty()) {
            message += String.format(" (한도 초과 또는 미지원 파일 %d개 제외)", excluded.size());
        }

        return new LmsExportPrepareResponse(selectedCourseMaterials.size(), accumulatedCount, accumulatedBytes,
                selectedCourseMaterials, excluded, message);
    }

    @Transactional
    public LmsExportConfirmResponse confirm(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new UnauthorizedException();
        }

        ActionAudit claimed = actionService.claimPendingAction(studentId);
        SelectionPayload payload = actionService.payload(claimed, SelectionPayload.class);

        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        String tokenHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            tokenHash = HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize selection payload", e);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getDownloadTtl());

        LmsExportJob job = LmsExportJob.createQueued(studentId, tokenHash, payloadJson, now, expiresAt);
        LmsExportJob savedJob = jobRepository.save(job);

        actionService.completeAction(claimed, ActionService.OUTCOME_SUCCESS, "export job " + savedJob.getId() + " queued");

        String downloadUrl = properties.getPublicBaseUrl() + "/api/lms/exports/" + savedJob.getId() + "/download?token=" + rawToken;

        return new LmsExportConfirmResponse(
                savedJob.getId(),
                payload.selections().size(),
                payload.totalBytes(), // estimate captured at prepare time (sum of known material sizes)
                expiresAt.toString(),
                downloadUrl,
                ""
        );
    }
}
