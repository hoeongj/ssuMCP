package com.ssuai.global.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ssuai.kafka")
public class ToolCallKafkaProperties {

    private boolean enabled = false;
    private String bootstrapServers = "";
    private String topic = "mcp.toolcall.events.v1";
    private int partitions = 6;
    private int queueCapacity = 1000;
    private long maxBlockMs = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getMaxBlockMs() {
        return maxBlockMs;
    }

    public void setMaxBlockMs(long maxBlockMs) {
        this.maxBlockMs = maxBlockMs;
    }
}
