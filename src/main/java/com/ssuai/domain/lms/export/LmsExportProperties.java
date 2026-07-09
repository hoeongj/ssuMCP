package com.ssuai.domain.lms.export;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.lms.export")
public class LmsExportProperties {

    private int maxFilesPerExport = 500;
    private long maxBytesPerExport = 2L * 1024 * 1024 * 1024; // 2GB
    private long maxBytesPerFile = 200L * 1024 * 1024; // 200MB hard cap per single remote file
    private Duration downloadTtl = Duration.ofMinutes(20);
    private String tempDir = System.getProperty("java.io.tmpdir") + "/ssuai-lms-export";
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofMinutes(5);
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

    public long getMaxBytesPerFile() {
        return maxBytesPerFile;
    }

    public void setMaxBytesPerFile(long maxBytesPerFile) {
        this.maxBytesPerFile = maxBytesPerFile;
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

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
