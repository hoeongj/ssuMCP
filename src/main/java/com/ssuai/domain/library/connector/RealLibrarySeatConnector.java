package com.ssuai.domain.library.connector;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.LibrarySeatZone;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.global.exception.ConnectorException;
import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorRateLimitedException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.ErrorCode;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.RetryAfter;
import com.ssuai.global.resilience.PyxisResilience;

/**
 * Fetches real seat data from oasis.ssu.ac.kr via the Pyxis API.
 * Requires a valid Pyxis-Auth-Token obtained after the user logs into
 * oasis on their browser (captured via the "도서관 연동" modal in the
 * ssuAI frontend).
 *
 * <p>Field names in the Pyxis seat-rooms response are verified against
 * {@code src/test/resources/library/seat-rooms.json}. If the upstream
 * API shape changes, update that fixture and this parser together.
 */
@Component
@ConditionalOnProperty(name = "ssuai.connector.library-seat", havingValue = "real")
public class RealLibrarySeatConnector implements LibrarySeatConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLibrarySeatConnector.class);

    private static final String SEAT_ROOMS_PATH =
            "/pyxis-api/1/seat-rooms?smufMethodCode=PC&branchGroupId=1";
    private static final String NEED_LOGIN_CODE = "error.authentication.needLogin";

    private static final Map<Integer, List<String>> ROOM_SEAT_CODES;

    static {
        Map<Integer, List<String>> map = new LinkedHashMap<>();
        map.put(54, numericCodes(1, 232));  // 오픈열람실(2F)
        map.put(53, numericCodes(1, 110));  // 숭실스퀘어ON(2F)
        map.put(57, numericCodes(1, 245));  // 마루열람실(6F)
        map.put(58, numericCodes(1, 62));   // 대학원열람실(6F)
        map.put(59, List.of("R1","R2","R3","R4","R5","R6"));  // 리클라이너(5F)
        map.put(60, numericCodes(1, 98));   // 숭실멀티라운지(5F)
        ROOM_SEAT_CODES = Collections.unmodifiableMap(map);
    }

    private static List<String> numericCodes(int from, int to) {
        List<String> codes = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            codes.add(String.valueOf(i));
        }
        return Collections.unmodifiableList(codes);
    }

    private final LibrarySeatProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final PyxisResilience pyxisResilience;

    public RealLibrarySeatConnector(
            LibrarySeatProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("librarySeatRestClient") RestClient restClient,
            PyxisResilience pyxisResilience
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.pyxisResilience = pyxisResilience;
    }

    @Override
    public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
        String body = callUpstream(token);
        return parseBody(body, floor);
    }

    @Override
    public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
        String body = callRoomSeatsUpstream(roomId, token);
        return parseRoomSeatsBody(body);
    }

    private String callUpstream(String token) {
        randomDelay();
        return pyxisResilience.read(PyxisResilience.principalOf(token), () -> {
            try {
                return restClient.get()
                        .uri(SEAT_ROOMS_PATH)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Pyxis-Auth-Token", token != null ? token : "")
                        .header("Referer", properties.getReferer())
                        .header("Accept-Language", "ko")
                        .retrieve()
                        .body(String.class);
            } catch (ResourceAccessException exception) {
                log.warn("library seat connector timeout/io");
                throw new ConnectorTimeoutException(exception);
            } catch (RestClientResponseException exception) {
                HttpStatusCode status = exception.getStatusCode();
                log.warn("library seat connector http error: status={}", status.value());
                if (status.is5xxServerError()) {
                    throw new ConnectorUnavailableException(exception);
                }
                if (status.value() == 429) {
                    Duration retryAfter = RetryAfter.parse(exception.getResponseHeaders() != null
                            ? exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER) : null).orElse(null);
                    throw new ConnectorRateLimitedException(retryAfter, exception);
                }
                throw new ConnectorParseException(exception);
            }
        });
    }


    private static void randomDelay() {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 1200));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private LibrarySeatStatusResponse parseBody(String body, LibraryFloor requestedFloor) {
        if (body == null || body.isBlank()) {
            throw new ConnectorParseException();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ConnectorParseException(exception);
        }

        if (!root.path("success").asBoolean(false)) {
            String code = root.path("code").asText("");
            if (NEED_LOGIN_CODE.equals(code)) {
                log.info("library seat upstream returned needLogin — token expired or invalid");
                throw new LibraryAuthRequiredException();
            }
            log.warn("library seat upstream returned success=false: code={}", code);
            throw new ConnectorParseException();
        }

        JsonNode list = root.path("data").path("list");
        if (!list.isArray()) {
            throw new ConnectorParseException();
        }

        int totalSeats = 0;
        int availableSeats = 0;
        int reservedSeats = 0;
        int outOfServiceSeats = 0;
        List<LibrarySeatZone> zones = new ArrayList<>();

        for (JsonNode room : list) {
            if (room.path("floor").asInt(Integer.MIN_VALUE) != requestedFloor.code()) {
                continue;
            }
            JsonNode seats = room.path("seats");
            int roomId       = room.path("id").asInt(-1);
            int roomTotal    = seats.path("total").asInt(0);
            int roomAvail    = seats.path("available").asInt(0);
            int roomOccupied = seats.path("occupied").asInt(0);
            int roomWaiting  = seats.path("waiting").asInt(0);
            int roomFixed    = seats.path("unavailable").asInt(0);

            totalSeats        += roomTotal;
            availableSeats    += roomAvail;
            reservedSeats     += roomOccupied + roomWaiting;
            outOfServiceSeats += roomFixed;

            String zoneName = textOr(room.path("name"), requestedFloor.displayLabel());
            List<String> seatCodes = roomAvail > 0
                    ? ROOM_SEAT_CODES.getOrDefault(roomId, List.of())
                    : List.of();
            zones.add(new LibrarySeatZone(zoneName, roomTotal, roomAvail, seatCodes, List.of()));
        }

        if (totalSeats == 0 && zones.isEmpty()) {
            log.warn("library seat: no rooms matched floor={}", requestedFloor.code());
        }

        return new LibrarySeatStatusResponse(
                requestedFloor.code(),
                requestedFloor.displayLabel(),
                totalSeats,
                availableSeats,
                reservedSeats,
                outOfServiceSeats,
                Instant.now(),
                zones
        );
    }

    private String callRoomSeatsUpstream(int roomId, String token) {
        return pyxisResilience.read(PyxisResilience.principalOf(token), () -> {
            try {
                return restClient.get()
                        .uri("/pyxis-api/1/api/rooms/{roomId}/seats", roomId)
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Pyxis-Auth-Token", token != null ? token : "")
                        .header("Referer", properties.getReferer())
                        .header("Accept-Language", "ko")
                        .retrieve()
                        .body(String.class);
            } catch (ResourceAccessException exception) {
                log.warn("library per-seat connector timeout/io: roomId={}", roomId);
                throw new ConnectorTimeoutException(exception);
            } catch (RestClientResponseException exception) {
                HttpStatusCode status = exception.getStatusCode();
                log.warn("library per-seat connector http error: roomId={} status={}", roomId, status.value());
                if (status.is5xxServerError()) {
                    throw new ConnectorUnavailableException(exception);
                }
                if (status.value() == 429) {
                    Duration retryAfter = RetryAfter.parse(exception.getResponseHeaders() != null
                            ? exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER) : null).orElse(null);
                    throw new ConnectorRateLimitedException(retryAfter, exception);
                }
                throw new ConnectorParseException(exception);
            }
        });
    }

    private List<PyxisSeatInfo> parseRoomSeatsBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ConnectorParseException();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ConnectorParseException(exception);
        }
        if (!root.path("success").asBoolean(false)) {
            String code = root.path("code").asText("");
            if (NEED_LOGIN_CODE.equals(code)) {
                throw new LibraryAuthRequiredException();
            }
            throw new ConnectorParseException();
        }
        JsonNode list = root.path("data").path("list");
        if (!list.isArray()) {
            throw new ConnectorParseException();
        }
        List<PyxisSeatInfo> seats = new ArrayList<>();
        for (JsonNode seat : list) {
            int id = seat.path("id").asInt(-1);
            String code = seat.path("code").asText("");
            boolean isActive = seat.path("isActive").asBoolean(false);
            boolean isOccupied = seat.path("isOccupied").asBoolean(false);
            JsonNode chargeStateNode = seat.path("seatChargeState");
            String chargeState = (chargeStateNode.isNull() || chargeStateNode.isMissingNode())
                    ? null : chargeStateNode.asText();
            int remainingTime = seat.path("remainingTime").asInt(0);
            int chargeTime = seat.path("chargeTime").asInt(0);
            String seatTypeName = seat.path("seatType").path("name").asText("일반용");

            String status;
            if (!isActive) {
                status = "inactive";
            } else if (isOccupied && "TEMP_CHARGE".equals(chargeState)) {
                status = "away";
            } else if (isOccupied) {
                status = "occupied";
            } else {
                status = "available";
            }

            seats.add(new PyxisSeatInfo(id, code, seatTypeName, status, remainingTime, chargeTime));
        }
        return Collections.unmodifiableList(seats);
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        String text = node.asText("");
        return text.isBlank() ? fallback : text;
    }
}
