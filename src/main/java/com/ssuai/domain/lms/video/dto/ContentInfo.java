package com.ssuai.domain.lms.video.dto;

public record ContentInfo(
        String contentId,
        String title,
        String storyGuid,
        String mp4Filename,
        String mediaUriTemplate,
        String webFilesUrl,
        int durationSeconds) {

    public String mp4Url() {
        return mediaUriTemplate.replace("[MEDIA_FILE]", mp4Filename);
    }
}
