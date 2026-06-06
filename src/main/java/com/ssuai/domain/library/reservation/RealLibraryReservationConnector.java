package com.ssuai.domain.library.reservation;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@Component
@ConditionalOnProperty(name = "ssuai.connector.library-reservation", havingValue = "real")
public class RealLibraryReservationConnector implements LibraryReservationConnector {

    private static final Logger log = LoggerFactory.getLogger(RealLibraryReservationConnector.class);

    // Verified via DevTools on oasis.ssu.ac.kr (2026-06-06)
    private static final String CURRENT_CHARGE_PATH = "/pyxis-api/1/api/seat-charges";
    private static final String RESERVE_PATH = "/pyxis-api/1/api/seat-charges";
    private static final String DISCHARGE_PATH = "/pyxis-api/1/api/seat-discharges";
    private static final String NO_RECORD_CODE = "success.noRecord";
    private static final String NEED_LOGIN_CODE = "error.authentication.needLogin";

    private final LibraryReservationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RealLibraryReservationConnector(
            LibraryReservationProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("libraryReservationRestClient") RestClient restClient
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public Optional<LibraryReservationResult> getCurrentCharge(String pyxisAuthToken) {
        try {
            String body = get(pyxisAuthToken, CURRENT_CHARGE_PATH, properties.getDischargeReferer());
            return parseCurrentChargeResponse(body);
        } catch (LibraryAuthRequiredException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("library getCurrentCharge failed", exception);
            return Optional.empty();
        }
    }

    @Override
    public LibraryReservationResult reserve(String pyxisAuthToken, LibraryReservationRequest request) {
        Map<String, Object> body = Map.of(
                "seatId", request.seatId(),
                "smufMethodCode", "PC"
        );
        String response = post(pyxisAuthToken, RESERVE_PATH, properties.getReferer(), body);
        return parseReserveResponse(response);
    }

    @Override
    public void discharge(String pyxisAuthToken, long chargeId) {
        Map<String, Object> body = Map.of(
                "seatCharge", chargeId,
                "smufMethodCode", "PC"
        );
        String response = post(pyxisAuthToken, DISCHARGE_PATH, properties.getDischargeReferer(), body);
        parseDischargeResponse(response);
    }

    private String get(String pyxisAuthToken, String path, String referer) {
        try {
            return restClient.get()
                    .uri(path)
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("Pyxis-Auth-Token", pyxisAuthToken != null ? pyxisAuthToken : "")
                    .header("Referer", referer)
                    .header("Accept-Language", "ko")
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            log.warn("library reservation connector timeout/io: path={}", path);
            throw new ConnectorTimeoutException(exception);
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            log.warn("library reservation connector http error: path={} status={}", path, status.value());
            if (status.is5xxServerError()) {
                throw new ConnectorUnavailableException(exception);
            }
            throw new ConnectorParseException(exception);
        }
    }

    private String post(String pyxisAuthToken, String path, String referer, Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Pyxis-Auth-Token", pyxisAuthToken != null ? pyxisAuthToken : "")
                    .header("Referer", referer)
                    .header("Accept-Language", "ko")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            log.warn("library reservation connector timeout/io: path={}", path);
            throw new ConnectorTimeoutException(exception);
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            log.warn("library reservation connector http error: path={} status={}", path, status.value());
            if (status.is5xxServerError()) {
                throw new ConnectorUnavailableException(exception);
            }
            throw new ConnectorParseException(exception);
        }
    }

    private Optional<LibraryReservationResult> parseCurrentChargeResponse(String body) {
        JsonNode root = parseJson(body);
        if (NO_RECORD_CODE.equals(root.path("code").asText(""))) {
            return Optional.empty();
        }
        if (!root.path("success").asBoolean(false)) {
            checkNeedLogin(root);
            return Optional.empty();
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty();
        }
        return Optional.of(parseChargeData(data));
    }

    private LibraryReservationResult parseReserveResponse(String body) {
        JsonNode root = parseJson(body);
        if (!root.path("success").asBoolean(false)) {
            checkNeedLogin(root);
            log.warn("library reservation upstream returned success=false: code={}", root.path("code").asText(""));
            throw new ConnectorParseException();
        }
        return parseChargeData(root.path("data"));
    }

    private static LibraryReservationResult parseChargeData(JsonNode data) {
        long chargeId = data.path("id").asLong(0);
        String roomName = data.path("room").path("name").asText("");
        String seatCode = data.path("seat").path("code").asText("");
        String beginTime = data.path("beginTime").asText("");
        String endTime = data.path("endTime").asText("");
        return new LibraryReservationResult(chargeId, roomName, seatCode, beginTime, endTime);
    }

    private void parseDischargeResponse(String body) {
        JsonNode root = parseJson(body);
        if (!root.path("success").asBoolean(false)) {
            checkNeedLogin(root);
            log.warn("library discharge upstream returned success=false: code={}", root.path("code").asText(""));
            throw new ConnectorParseException();
        }
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            throw new ConnectorParseException();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new ConnectorParseException(exception);
        }
    }

    private void checkNeedLogin(JsonNode root) {
        if (NEED_LOGIN_CODE.equals(root.path("code").asText(""))) {
            log.info("library reservation upstream returned needLogin");
            throw new LibraryAuthRequiredException();
        }
    }
}
