package com.ssuai.domain.lms.util;

import java.util.Set;

import com.ssuai.domain.lms.dto.LmsMaterial;

public final class MaterialFileFilter {

    private static final Set<String> WHITELIST = Set.of(
            "pdf", "ppt", "pptx", "doc", "docx", "hwp", "hwpx", "txt", "csv",
            "xls", "xlsx", "zip", "7z", "rar", "java", "py", "c", "cpp",
            "md", "html", "json", "xml", "png", "jpg", "jpeg", "gif"
    );

    private static final Set<String> BLACKLIST_CONTENT_TYPES = Set.of(
            "everlec", "video", "audio", "mp4", "mp3"
    );

    private MaterialFileFilter() {
    }

    public static boolean isIncluded(LmsMaterial material) {
        if (material == null) {
            return false;
        }
        String fileName = material.fileName();
        if (fileName == null || fileName.isBlank()) {
            return false;
        }

        // Belt-and-suspenders: check content type
        String contentType = material.contentType();
        if (contentType != null && !contentType.isBlank()) {
            String lowerType = contentType.trim().toLowerCase();
            for (String blacklisted : BLACKLIST_CONTENT_TYPES) {
                if (lowerType.contains(blacklisted)) {
                    return false;
                }
            }
        }

        String extension = extensionOf(fileName);
        if (extension.isBlank()) {
            return false;
        }

        return WHITELIST.contains(extension);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase().trim();
    }
}
