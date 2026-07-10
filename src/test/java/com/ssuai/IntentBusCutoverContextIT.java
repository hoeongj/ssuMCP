package com.ssuai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import com.ssuai.domain.library.reservation.intent.KafkaLibraryIntentStatusBus;
import com.ssuai.domain.library.reservation.intent.LibraryIntentSseRegistry;
import com.ssuai.domain.library.reservation.intent.LibraryIntentStatusBus;

/**
 * Regression guard for the Phase 2-C live cutover (2026-07-10): with the intent-bus flag ON the FULL
 * application context must start. The first cutover crash-looped because two LibraryIntentStatusBus
 * beans (libraryIntentStatusBus + kafkaLibraryIntentStatusBus) made LibraryIntentSseRegistry's
 * single-arg constructor ambiguous — a wiring the earlier EmbeddedKafka IT never loaded together.
 * @Primary on the canonical bus resolves it; this test loads the real wiring the flag flip exercises.
 */
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {"mcp.toolcall.events.v1", "library.reservation.events.v1"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest(properties = {
        "ssuai.kafka.enabled=true",
        "ssuai.kafka.intent-bus.enabled=true",
        "ssuai.kafka.intent-bus.auto-offset-reset=earliest"
})
class IntentBusCutoverContextIT {

    @Autowired
    private LibraryIntentSseRegistry sseRegistry;

    @Autowired
    private LibraryIntentStatusBus intentStatusBus;

    @Test
    void contextLoadsAndResolvesSingleKafkaBackedIntentBus() {
        assertThat(sseRegistry).isNotNull();
        assertThat(intentStatusBus).isInstanceOf(KafkaLibraryIntentStatusBus.class);
    }
}
