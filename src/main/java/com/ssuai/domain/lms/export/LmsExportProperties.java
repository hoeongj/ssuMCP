package com.ssuai.domain.lms.export;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.lms.export")
public class LmsExportProperties {

    private int maxFilesPerExport = 500;
    private long maxBytesPerExport = 2L * 1024 * 1024 * 1024; // 2GB
    private Duration downloadTtl = Duration.ofMinutes(20);
    private String tempDir = System.getProperty("java.io.tmpdir") + "/ssuai-lms-export";
    private Duration pollInterval = Duration.ofSeconds(5);
    private String publicBaseUrl = "";

    public int getMaxFilesPerExport() {
        return maxFilesPerExport;
    }

    public void setMaxFilesPerExport(int maxFilesPerExport) {
        this.maxFilesPerExport = maxFilesPerExport;
    }

    public long getMaxBytesPerExport() {
        return maxBytesPerExport;
    }

    public void setMaxBytesPerExport(long maxBytesPerExport) {
        this.maxBytesPerExport = maxBytesPerExport;
    }

    public Duration getDownloadTtl() {
        return downloadTtl;
    }

    public void setDownloadTtl(Duration downloadTtl) {
        this.downloadTtl = downloadTtl;
    }

    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(String tempDir) {
        this.tempDir = tempDir;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
