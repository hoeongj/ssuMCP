package com.ssuai.domain.library.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import com.ssuai.domain.library.dto.PyxisSeatInfo;

class RedissonLibraryRoomSeatL2Cache implements LibraryRoomSeatL2Cache {

    private static final TypeReference<List<PyxisSeatInfo>> SEAT_LIST_TYPE = new TypeReference<>() {};

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final LibraryRedisProperties properties;

    RedissonLibraryRoomSeatL2Cache(
            RedissonClient redissonClient,
            ObjectMapper objectMapper,
            LibraryRedisProperties properties) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<List<PyxisSeatInfo>> get(int roomId, boolean authenticated) {
        RBucket<String> bucket = redissonClient.getBucket(
                properties.roomSeatCacheKey(roomId, authenticated),
                StringCodec.INSTANCE);
        String payload = bucket.get();
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(List.copyOf(objectMapper.readValue(payload, SEAT_LIST_TYPE)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("library room seat L2 cache payload cannot be parsed", exception);
        }
    }

    @Override
    public void put(int roomId, boolean authenticated, List<PyxisSeatInfo> seats, Duration ttl) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(
                    properties.roomSeatCacheKey(roomId, authenticated),
                    StringCodec.INSTANCE);
            bucket.set(objectMapper.writeValueAsString(seats == null ? List.of() : seats), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("library room seat L2 cache payload cannot be serialized", exception);
        }
    }
}
