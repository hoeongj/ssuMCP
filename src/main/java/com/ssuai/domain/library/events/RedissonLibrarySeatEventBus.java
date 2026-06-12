package com.ssuai.domain.library.events;

import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ssuai.domain.library.redis.LibraryRedisProperties;

public class RedissonLibrarySeatEventBus implements LibrarySeatEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedissonLibrarySeatEventBus.class);

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final LibraryRedisProperties properties;

    public RedissonLibrarySeatEventBus(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            LibraryRedisProperties properties) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(LibrarySeatEvent event) {
        try {
            topic().publish(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("library seat event cannot be serialized", exception);
        }
    }

    @Override
    public Subscription subscribe(Consumer<LibrarySeatEvent> listener) {
        RTopic topic = topic();
        int listenerId = topic.addListener(String.class, (channel, payload) -> {
            try {
                listener.accept(objectMapper.readValue(payload, LibrarySeatEvent.class));
            } catch (RuntimeException | JsonProcessingException exception) {
                log.warn("library seat event subscriber failed: channel={}", channel, exception);
            }
        });
        return () -> topic.removeListener(listenerId);
    }

    private RTopic topic() {
        return redissonClient.getTopic(properties.getSeatEventChannel(), StringCodec.INSTANCE);
    }
}
