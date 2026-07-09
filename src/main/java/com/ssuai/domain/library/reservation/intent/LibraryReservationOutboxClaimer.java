package com.ssuai.domain.library.reservation.intent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LibraryReservationOutboxClaimer {

    private final LibraryReservationOutboxRepository repository;
    private final LibraryReservationIntentProperties properties;
    private final Clock clock;
    private final String ownerId;

    public LibraryReservationOutboxClaimer(
            LibraryReservationOutboxRepository repository,
            LibraryReservationIntentProperties properties,
            Clock clock,
            @Value("${HOSTNAME:${random.uuid}}") String ownerId) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.ownerId = ownerId;
    }

    /**
     * The oldest unpublished row is the batch guard. Only the pod that locks that row may claim
     * the next ordered batch, preserving the relay's existing global outbox-id order.
     */
    @Transactional
    public List<LibraryReservationOutbox> claimBatch() {
        Instant now = clock.instant();
        Instant leaseCutoff = now.minus(properties.getRelayLease());
        if (repository.findOldestUnpublishedForUpdate(leaseCutoff).isEmpty()) {
            return List.of();
        }

        List<LibraryReservationOutbox> claimed =
                repository.findClaimableForUpdate(leaseCutoff, properties.getRelayBatchSize());
        claimed.forEach(outbox -> outbox.claim(ownerId, now));
        return List.copyOf(claimed);
    }

    @Transactional
    public void markPublished(List<LibraryReservationOutbox> claimed) {
        if (claimed.isEmpty()) {
            return;
        }
        repository.markClaimedPublished(
                claimed.stream().map(LibraryReservationOutbox::getId).toList(),
                ownerId,
                clock.instant());
    }
}
