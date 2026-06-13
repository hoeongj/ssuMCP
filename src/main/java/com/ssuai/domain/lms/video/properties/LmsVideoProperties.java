package com.ssuai.domain.lms.video.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.lms-video")
public class LmsVideoProperties {

    private String connector = "mock";
    private String commonsContentPhpUrl =
            "https://commons.ssu.ac.kr/viewer/ssplayer/uniplayer_support/content.php";
    private String groqApiKey = "";
    private String groqBaseUrl = "https://api.groq.com/openai/v1";
    private String sttModel = "whisper-large-v3";
    private String sttLanguage = "ko";
    private int chunkDurationSeconds = 600;
    private int downloadTimeoutSeconds = 120;

    public boolean isGroqUsable() {
        return groqApiKey != null && !groqApiKey.isBlank();
    }

    public String getConnector() {
        return connector;
    }

    public void setConnector(String connector) {
        this.connector = trimmed(connector);
    }

    public String getCommonsContentPhpUrl() {
        return commonsContentPhpUrl;
    }

    public void setCommonsContentPhpUrl(String commonsContentPhpUrl) {
        this.commonsContentPhpUrl = trimmed(commonsContentPhpUrl);
    }

    public String getGroqApiKey() {
        return groqApiKey;
    }

    public void setGroqApiKey(String groqApiKey) {
        this.groqApiKey = trimmed(groqApiKey);
    }

    public String getGroqBaseUrl() {
        return groqBaseUrl;
    }

    public void setGroqBaseUrl(String groqBaseUrl) {
        this.groqBaseUrl = trimmed(groqBaseUrl);
    }

    public String getSttModel() {
        return sttModel;
    }

    public void setSttModel(String sttModel) {
        this.sttModel = trimmed(sttModel);
    }

    public String getSttLanguage() {
        return sttLanguage;
    }

    public void setSttLanguage(String sttLanguage) {
        this.sttLanguage = trimmed(sttLanguage);
    }

    public int getChunkDurationSeconds() {
        return chunkDurationSeconds;
    }

    public void setChunkDurationSeconds(int chunkDurationSeconds) {
        this.chunkDurationSeconds = chunkDurationSeconds;
    }

    public int getDownloadTimeoutSeconds() {
        return downloadTimeoutSeconds;
    }

    public void setDownloadTimeoutSeconds(int downloadTimeoutSeconds) {
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
