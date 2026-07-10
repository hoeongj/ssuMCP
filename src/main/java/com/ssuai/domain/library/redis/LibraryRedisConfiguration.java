package com.ssuai.domain.library.redis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.ssuai.domain.library.events.LibrarySeatEventBus;
import com.ssuai.domain.library.events.RedissonLibrarySeatEventBus;
import com.ssuai.domain.library.reservation.intent.KafkaLibraryIntentStatusBus;
import com.ssuai.domain.library.reservation.intent.LibraryIntentStatusBus;
import com.ssuai.domain.library.reservation.intent.RedissonLibraryIntentStatusBus;

@Configuration
class LibraryRedisConfiguration {

    @Bean
    RedissonAutoConfigurationCustomizer redissonLazyInitializationCustomizer() {
        return config -> config.setLazyInitialization(true);
    }

    @Bean
    LibraryRoomSeatL2Cache libraryRoomSeatL2Cache(
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            LibraryRedisProperties properties) {
        RedissonClient redissonClient = properties.isEnabled() ? redissonClientProvider.getIfAvailable() : null;
        return redissonClient == null
                ? LibraryRoomSeatL2Cache.noop()
                : new RedissonLibraryRoomSeatL2Cache(redissonClient, objectMapper(objectMapperProvider), properties);
    }

    @Bean
    LibrarySeatEventBus librarySeatEventBus(
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            LibraryRedisProperties properties) {
        RedissonClient redissonClient = properties.isEnabled() ? redissonClientProvider.getIfAvailable() : null;
        return redissonClient == null
                ? LibrarySeatEventBus.noop()
                : new RedissonLibrarySeatEventBus(redissonClient, objectMapper(objectMapperProvider), properties);
    }

    @Bean
    @Primary
    LibraryIntentStatusBus libraryIntentStatusBus(
            ObjectProvider<KafkaLibraryIntentStatusBus> kafkaBusProvider,
            ObjectProvider<RedissonClient> redissonClientProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            LibraryRedisProperties properties) {
        // @Primary: when the flag is on, IntentBusKafkaConfig also registers `kafkaLibraryIntentStatusBus`
        // as a LibraryIntentStatusBus bean, so single-arg consumers (LibraryIntentSseRegistry,
        // LibraryReservationEventListener) would otherwise be ambiguous. This canonical bean IS the one
        // to inject — it already returns the Kafka bus when present, and the raw kafka bean is just its
        // delegate. (Regression: the intent-bus cutover crash-looped on this exact ambiguity, 2026-07-10.)
        // Phase 2-C (ADR 0091): when ssuai.kafka.intent-bus.enabled=true the Kafka bus bean exists and
        // graduates the cross-pod fan-out from Redisson RTopic to Kafka. Falling back to Redisson (then
        // noop) keeps the flag fully reversible — flipping it off restores the RTopic path with no code
        // change, and neither path is per-pod-broken.
        KafkaLibraryIntentStatusBus kafkaBus = kafkaBusProvider.getIfAvailable();
        if (kafkaBus != null) {
            return kafkaBus;
        }
        RedissonClient redissonClient = properties.isEnabled() ? redissonClientProvider.getIfAvailable() : null;
        return redissonClient == null
                ? LibraryIntentStatusBus.noop()
                : new RedissonLibraryIntentStatusBus(redissonClient, objectMapper(objectMapperProvider), properties);
    }

    @Bean
    LibraryDistributedLockClient libraryDistributedLockClient(
            ObjectProvider<RedissonClient> redissonClientProvider,
            LibraryRedisProperties properties) {
        RedissonClient redissonClient = properties.isEnabled() ? redissonClientProvider.getIfAvailable() : null;
        return redissonClient == null
                ? LibraryDistributedLockClient.noop()
                : new RedissonLibraryDistributedLockClient(redissonClient);
    }

    private ObjectMapper objectMapper(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper existing = objectMapperProvider.getIfAvailable();
        if (existing != null) {
            return existing;
        }
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }
}
