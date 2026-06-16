package com.ssuai.domain.lms.connector;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

import com.ssuai.domain.auth.lms.LmsCookies;
import com.ssuai.domain.lms.dto.ContentDownloadInfo;
import com.ssuai.domain.lms.dto.LmsCourse;
import com.ssuai.domain.lms.dto.LmsMaterial;

public interface LmsMaterialsConnector {
    List<LmsCourse> fetchCourses(String studentId, LmsCookies cookies, long termId);
    List<LmsMaterial> fetchMaterials(String studentId, LmsCookies cookies, LmsCourse course);
    /** Resolves a content_id to its absolute download URL; empty if unavailable. */
    Optional<ContentDownloadInfo> resolveDownload(LmsCookies cookies, String contentId);
    void download(LmsCookies cookies, String absoluteDownloadUrl, OutputStream destination);
}
