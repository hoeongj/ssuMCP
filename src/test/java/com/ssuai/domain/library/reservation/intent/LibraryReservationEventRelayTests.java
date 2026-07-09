package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class LibraryReservationEventRelayTests {

    @Test
    void dispatchesClaimedEventsAndCompletesBatch() {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        LibraryReservationOutbox outbox = new LibraryReservationOutbox(
                LibraryReservationIntentEventType.WAIT_REGISTERED,
                1L,
                "{\"intentId\":1}",
                now.minusSeconds(1));
        ReflectionTestUtils.setField(outbox, "id", 9L);

        LibraryReservationOutboxClaimer claimer = mock(LibraryReservationOutboxClaimer.class);
        LibraryReservationIntentMetrics metrics = mock(LibraryReservationIntentMetrics.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(claimer.claimBatch()).thenReturn(List.of(outbox));

        LibraryReservationEventRelay relay = new LibraryReservationEventRelay(
                claimer,
                metrics,
                publisher,
                Clock.fixed(now, ZoneOffset.UTC));

        int count = relay.relay();

        assertThat(count).isEqualTo(1);
        verify(publisher).publishEvent(new LibraryReservationOutboxEvent(
                9L,
                LibraryReservationIntentEventType.WAIT_REGISTERED,
                1L,
                "{\"intentId\":1}",
                now.minusSeconds(1)));
        verify(metrics).countRelay(LibraryReservationIntentEventType.WAIT_REGISTERED);
        verify(claimer).markPublished(List.of(outbox));
    }
}
