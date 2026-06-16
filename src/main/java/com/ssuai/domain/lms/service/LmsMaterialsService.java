package com.ssuai.domain.lms.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.auth.lms.LmsSessionStore;
import com.ssuai.domain.lms.connector.LmsMaterialsConnector;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsCourseMaterials;
import com.ssuai.domain.lms.dto.LmsMaterial;
import com.ssuai.domain.lms.dto.LmsMaterialGroup;
import com.ssuai.domain.lms.dto.LmsTermItem;
import com.ssuai.domain.lms.util.MaterialFileFilter;
import com.ssuai.global.exception.LmsSessionExpiredException;
import com.ssuai.global.exception.UnauthorizedException;

@Service
public class LmsMaterialsService {

    private final LmsMaterialsConnector connector;
    private final LmsSessionStore sessionStore;
    private final LmsAssignmentsService assignmentsService;

    public LmsMaterialsService(LmsMaterialsConnector connector, LmsSessionStore sessionStore,
                               LmsAssignmentsService assignmentsService) {
        this.connector = connector;
        this.sessionStore = sessionStore;
        this.assignmentsService = assignmentsService;
    }

    public List<LmsCourse> listCourses(String studentId, Long termId) {
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

        return connector.fetchCourses(studentId, cookies, resolvedTermId);
    }

    public List<LmsCourseMaterials> listMaterials(String studentId, List<Long> courseIds, Long termId) {
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
        if (courseIds != null && !courseIds.isEmpty()) {
            courses = courses.stream()
                    .filter(c -> courseIds.contains(c.id()))
                    .toList();
        }

        List<LmsCourseMaterials> result = new ArrayList<>();
        for (LmsCourse course : courses) {
            List<LmsMaterial> materials = connector.fetchMaterials(studentId, cookies, course);
            
            // Filter materials
            List<LmsMaterial> filtered = materials.stream()
                    .filter(MaterialFileFilter::isIncluded)
                    .toList();

            // Group by extension
            Map<String, List<LmsMaterial>> grouped = filtered.stream()
                    .collect(Collectors.groupingBy(m -> {
                        String ext = m.extension();
                        return ext == null ? "" : ext.toLowerCase().trim();
                    }));

            List<LmsMaterialGroup> groups = new ArrayList<>();
            int totalCount = filtered.size();
            long totalBytes = 0;

            for (Map.Entry<String, List<LmsMaterial>> entry : grouped.entrySet()) {
                String ext = entry.getKey();
                List<LmsMaterial> groupMaterials = entry.getValue().stream()
                        .sorted(Comparator.comparing(LmsMaterial::weekTitle, Comparator.nullsFirst(String::compareTo))
                                .thenComparing(LmsMaterial::title, Comparator.nullsFirst(String::compareTo)))
                        .toList();

                groups.add(new LmsMaterialGroup(ext, groupMaterials.size(), groupMaterials));
                for (LmsMaterial m : groupMaterials) {
                    if (m.sizeBytes() != null) {
                        totalBytes += m.sizeBytes();
                    }
                }
            }

            // Sort groups alphabetically by extension
            groups.sort(Comparator.comparing(LmsMaterialGroup::extension));

            result.add(new LmsCourseMaterials(course, groups, totalCount, totalBytes));
        }

        return result;
    }
}
