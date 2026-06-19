package com.ssuai.domain.library.reservation.intent;

import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ssuai.domain.library.redis.LibraryRedisProperties;

public class RedissonLibraryIntentStatusBus implements LibraryIntentStatusBus {

    private static final Logger log = LoggerFactory.getLogger(RedissonLibraryIntentStatusBus.class);

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final LibraryRedisProperties properties;

    public RedissonLibraryIntentStatusBus(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            LibraryRedisProperties properties) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(LibraryIntentStatusMessage message) {
        try {
            topic().publish(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("intent status message cannot be serialized", exception);
        }
    }

    @Override
    public Subscription subscribe(Consumer<LibraryIntentStatusMessage> listener) {
        RTopic topic = topic();
        int listenerId = topic.addListener(String.class, (channel, payload) -> {
            try {
                listener.accept(objectMapper.readValue(payload, LibraryIntentStatusMessage.class));
            } catch (RuntimeException | JsonProcessingException exception) {
                log.warn("intent status subscriber failed: channel={}", channel, exception);
            }
        });
        return () -> topic.removeListener(listenerId);
    }

    private RTopic topic() {
        return redissonClient.getTopic(properties.getIntentStatusChannel(), StringCodec.INSTANCE);
    }
}
