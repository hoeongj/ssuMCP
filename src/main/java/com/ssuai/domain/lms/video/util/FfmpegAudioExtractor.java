package com.ssuai.domain.lms.video.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ssuai.domain.lms.video.properties.LmsVideoProperties;
import com.ssuai.global.exception.ConnectorUnavailableException;

@Component
public class FfmpegAudioExtractor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegAudioExtractor.class);

    private final LmsVideoProperties properties;

    public FfmpegAudioExtractor(LmsVideoProperties properties) {
        this.properties = properties;
    }

    /**
     * Splits an MP4 file into 16kHz mono MP3 chunks. Caller must delete chunks.
     */
    public List<Path> extractChunks(Path videoFile, int totalDurationSeconds) {
        int chunkDuration = Math.max(1, properties.getChunkDurationSeconds());
        List<Path> chunks = new ArrayList<>();
        try {
            if (totalDurationSeconds <= 0 || totalDurationSeconds <= chunkDuration) {
                Path out = Files.createTempFile("ssuai-audio-", ".mp3");
                try {
                    runFfmpeg("-i", videoFile.toString(),
                            "-vn", "-ar", "16000", "-ac", "1", "-f", "mp3",
                            out.toString());
                } catch (RuntimeException exception) {
                    deleteQuietly(out);
                    throw exception;
                }
                chunks.add(out);
                return chunks;
            }

            int offset = 0;
            while (offset < totalDurationSeconds) {
                Path out = Files.createTempFile("ssuai-audio-" + offset + "-", ".mp3");
                try {
                    runFfmpeg("-ss", String.valueOf(offset),
                            "-i", videoFile.toString(),
                            "-t", String.valueOf(chunkDuration),
                            "-vn", "-ar", "16000", "-ac", "1", "-f", "mp3",
                            out.toString());
                } catch (RuntimeException exception) {
                    deleteQuietly(out);
                    throw exception;
                }
                chunks.add(out);
                offset += chunkDuration;
            }
            return chunks;
        } catch (IOException exception) {
            throw unavailable("ffmpeg temp file error: " + exception.getMessage(), exception);
        }
    }

    private void runFfmpeg(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                String snippet = output.length() > 500 ? output.substring(0, 500) : output;
                log.warn("ffmpeg exited with {}: {}", exit, snippet);
                throw unavailable("ffmpeg failed with exit code " + exit);
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unavailable("ffmpeg error: " + exception.getMessage(), exception);
        }
    }

    private static ConnectorUnavailableException unavailable(String message) {
        return unavailable(message, null);
    }

    private static ConnectorUnavailableException unavailable(String message, Throwable cause) {
        return new ConnectorUnavailableException(new IllegalStateException(message, cause));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
