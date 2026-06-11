package com.ssuai.domain.library.reservation.intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.auth.LibrarySessionStore;
import com.ssuai.domain.library.reservation.LibraryReservationConnector;
import com.ssuai.domain.library.reservation.LibraryReservationRequest;
import com.ssuai.domain.library.reservation.LibraryReservationResult;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

@Component
public class LibraryReservationWorker {

    private static final Logger log = LoggerFactory.getLogger(LibraryReservationWorker.class);

    private final LibraryReservationIntentTransactions transactions;
    private final LibraryReservationSeatSelector seatSelector;
    private final LibrarySessionStore sessionStore;
    private final LibraryReservationConnector reservationConnector;

    public LibraryReservationWorker(
            LibraryReservationIntentTransactions transactions,
            LibraryReservationSeatSelector seatSelector,
            LibrarySessionStore sessionStore,
            LibraryReservationConnector reservationConnector) {
        this.transactions = transactions;
        this.seatSelector = seatSelector;
        this.sessionStore = sessionStore;
        this.reservationConnector = reservationConnector;
    }

    @Scheduled(fixedDelayString = "#{@libraryReservationIntentProperties.pollInterval.toMillis()}")
    public void poll() {
        transactions.expireWaiting();
        recoverExpiredLeases();

        List<LibraryReservationIntent> claimed = transactions.claimWaitingBatch();
        if (claimed.isEmpty()) {
            return;
        }

        Map<Long, List<ReadyIntent>> bySeat = new LinkedHashMap<>();
        for (LibraryReservationIntent intent : claimed) {
            Optional<ReadyIntent> ready = prepare(intent);
            ready.ifPresent(value -> bySeat.computeIfAbsent(value.seatId(), ignored -> new ArrayList<>()).add(value));
        }
        bySeat.forEach(this::processSeatGroup);
    }

    private Optional<ReadyIntent> prepare(LibraryReservationIntent intent) {
        String token = sessionStore.token(intent.getSessionKey()).orElse(null);
        if (token == null) {
            transactions.failAuth(intent.getId(), "Library session token is missing or expired.");
            return Optional.empty();
        }
        try {
            if (intent.isImmediateReservation()) {
                return Optional.of(new ReadyIntent(intent.getId(), token, intent.getTargetSeatId()));
            }
            Optional<Long> seatId = seatSelector.findAvailableSeat(intent);
            if (seatId.isEmpty()) {
                transactions.returnToWaiting(intent.getId());
                return Optional.empty();
            }
            return Optional.of(new ReadyIntent(intent.getId(), token, seatId.get()));
        } catch (LibraryAuthRequiredException exception) {
            transactions.failAuth(intent.getId(), "Library session token is missing or expired.");
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("library reservation intent seat scan failed: intentId={}", intent.getId(), exception);
            transactions.failUpstream(intent.getId(), "Unable to read current library seat availability.");
            return Optional.empty();
        }
    }

    private void processSeatGroup(Long seatId, List<ReadyIntent> intents) {
        if (transactions.hasActiveCompletedImmediateAttemptForSeat(seatId)) {
            intents.forEach(intent -> transactions.failRace(
                    intent.intentId(),
                    seatId,
                    "Another recent immediate reservation intent already resolved this seat."));
            return;
        }
        ReadyIntent winner = intents.get(0);
        for (int index = 1; index < intents.size(); index++) {
            transactions.failRace(
                    intents.get(index).intentId(),
                    seatId,
                    "Another local wait intent already attempted this seat in the same worker tick.");
        }
        executeReservation(winner, seatId);
    }

    private void executeReservation(ReadyIntent intent, Long seatId) {
        try {
            LibraryReservationResult result = reservationConnector.reserve(
                    intent.token(), new LibraryReservationRequest(seatId));
            transactions.succeed(intent.intentId(), seatId, successMessage(result));
        } catch (LibrarySeatNotAvailableException exception) {
            transactions.failRace(intent.intentId(), seatId, "Seat was already taken upstream.");
        } catch (LibraryAuthRequiredException exception) {
            transactions.failAuth(intent.intentId(), "Library session was rejected by upstream.");
        } catch (ConnectorTimeoutException exception) {
            transactions.failUpstream(intent.intentId(), "Library reservation upstream timed out.");
        } catch (RuntimeException exception) {
            log.warn("library reservation intent reserve failed: intentId={} seatId={}",
                    intent.intentId(), seatId, exception);
            transactions.failUpstream(intent.intentId(), "Library reservation upstream failed.");
        }
    }

    private void recoverExpiredLeases() {
        List<LibraryReservationIntent> expiredLeases = transactions.claimExpiredLeases();
        for (LibraryReservationIntent intent : expiredLeases) {
            String token = sessionStore.token(intent.getSessionKey()).orElse(null);
            if (token == null) {
                transactions.failAuth(intent.getId(), "Library session token is missing while recovering an expired lease.");
                continue;
            }
            try {
                Optional<LibraryReservationResult> current = reservationConnector.getCurrentCharge(token);
                if (current.isPresent()) {
                    transactions.succeed(
                            intent.getId(),
                            intent.getTargetSeatId(),
                            "Recovered reservation after worker lease expiry: " + successMessage(current.get()));
                } else {
                    transactions.failUpstream(
                            intent.getId(),
                            "Worker lease expired and current charge did not confirm a reservation.");
                }
            } catch (LibraryAuthRequiredException exception) {
                transactions.failAuth(intent.getId(), "Library session was rejected while recovering an expired lease.");
            } catch (RuntimeException exception) {
                log.warn("library reservation intent reaper failed: intentId={}", intent.getId(), exception);
                transactions.failUpstream(intent.getId(), "Unable to verify reservation after worker lease expiry.");
            }
        }
    }

    private static String successMessage(LibraryReservationResult result) {
        return "%s %s reserved, chargeId=%d, time=%s~%s".formatted(
                result.roomName(),
                result.seatCode(),
                result.chargeId(),
                result.beginTime(),
                result.endTime());
    }

    private record ReadyIntent(Long intentId, String token, Long seatId) {
    }
}
