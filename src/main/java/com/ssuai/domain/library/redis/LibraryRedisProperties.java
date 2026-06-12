package com.ssuai.domain.library.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.library.redis")
public class LibraryRedisProperties {

    private boolean enabled = true;
    private String roomSeatCacheKeyPrefix = "ssuai:library:room-seats:v1";
    private String seatEventChannel = "ssuai.library.seat-events.v1";
    private String schedulerLockPrefix = "ssuai:library:scheduler:";
    private Duration schedulerLockWait = Duration.ZERO;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRoomSeatCacheKeyPrefix() {
        return roomSeatCacheKeyPrefix;
    }

    public void setRoomSeatCacheKeyPrefix(String roomSeatCacheKeyPrefix) {
        this.roomSeatCacheKeyPrefix = requireText(roomSeatCacheKeyPrefix, "roomSeatCacheKeyPrefix");
    }

    public String getSeatEventChannel() {
        return seatEventChannel;
    }

    public void setSeatEventChannel(String seatEventChannel) {
        this.seatEventChannel = requireText(seatEventChannel, "seatEventChannel");
    }

    public String getSchedulerLockPrefix() {
        return schedulerLockPrefix;
    }

    public void setSchedulerLockPrefix(String schedulerLockPrefix) {
        this.schedulerLockPrefix = requireText(schedulerLockPrefix, "schedulerLockPrefix");
    }

    public Duration getSchedulerLockWait() {
        return schedulerLockWait;
    }

    public void setSchedulerLockWait(Duration schedulerLockWait) {
        if (schedulerLockWait == null || schedulerLockWait.isNegative()) {
            throw new IllegalArgumentException("schedulerLockWait must be zero or positive");
        }
        this.schedulerLockWait = schedulerLockWait;
    }

    public String roomSeatCacheKey(int roomId, boolean authenticated) {
        return roomSeatCacheKeyPrefix + ":room:" + roomId + ":auth:" + (authenticated ? "1" : "0");
    }

    public String schedulerLockName(String jobName) {
        return schedulerLockPrefix + requireText(jobName, "jobName");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
