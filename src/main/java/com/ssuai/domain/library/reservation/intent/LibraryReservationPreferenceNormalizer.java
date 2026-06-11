package com.ssuai.domain.library.reservation.intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class LibraryReservationPreferenceNormalizer {

    private static final Set<String> SUPPORTED_ATTRIBUTES = Set.of(
            "window", "outlet", "standing", "edge", "quiet", "nearEntrance");

    private final ObjectMapper objectMapper;

    public LibraryReservationPreferenceNormalizer() {
        this(new ObjectMapper());
    }

    LibraryReservationPreferenceNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalizeFloor(String preferredFloor) {
        if (preferredFloor == null || preferredFloor.isBlank()) {
            return null;
        }
        String normalized = preferredFloor.trim().toUpperCase(Locale.ROOT);
        if (normalized.endsWith("F")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            int floor = Integer.parseInt(normalized);
            if (floor == 2 || floor == 5 || floor == 6) {
                return Integer.toString(floor);
            }
        } catch (NumberFormatException ignored) {
            // handled below
        }
        throw new IllegalArgumentException("floor must be one of 2, 5, 6, 2F, 5F, or 6F.");
    }

    public String normalizeRoomIds(String roomIds) {
        Set<Integer> parsed = parseRoomIds(roomIds);
        if (parsed.isEmpty()) {
            return null;
        }
        return writeJson(new ArrayList<>(parsed));
    }

    public Set<Integer> parseRoomIds(String roomIdsJson) {
        if (roomIdsJson == null || roomIdsJson.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        JsonNode root = readTreeOrNull(roomIdsJson);
        if (root != null && root.isArray()) {
            root.forEach(node -> ids.add(node.asInt()));
        } else {
            for (String token : roomIdsJson.split("[,\\s]+")) {
                if (!token.isBlank()) {
                    ids.add(Integer.parseInt(token.trim()));
                }
            }
        }
        ids.removeIf(id -> id <= 0);
        return Set.copyOf(ids);
    }

    public String normalizeSeatAttributes(String seatAttributes) {
        Set<String> parsed = parseAttributeTags(seatAttributes);
        if (parsed.isEmpty()) {
            return null;
        }
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        parsed.forEach(attribute -> normalized.put(attribute, true));
        return writeJson(normalized);
    }

    public Set<String> parseAttributeTags(String seatAttributesJson) {
        if (seatAttributesJson == null || seatAttributesJson.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        JsonNode root = readTreeOrNull(seatAttributesJson);
        if (root != null && root.isObject()) {
            root.properties().forEach(entry -> {
                if (entry.getValue().asBoolean(false)) {
                    addSupported(tags, entry.getKey());
                }
            });
        } else if (root != null && root.isArray()) {
            root.forEach(node -> addSupported(tags, node.asText()));
        } else {
            for (String token : seatAttributesJson.split("[,\\s]+")) {
                addSupported(tags, token);
            }
        }
        return Set.copyOf(tags);
    }

    private JsonNode readTreeOrNull(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void addSupported(Set<String> tags, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String normalized = normalizeAttribute(raw);
        if (!SUPPORTED_ATTRIBUTES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "seat_attributes supports: window, outlet, standing, edge, quiet, nearEntrance.");
        }
        tags.add(normalized);
    }

    private String normalizeAttribute(String raw) {
        String compact = raw.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if ("nearentrance".equals(compact)) {
            return "nearEntrance";
        }
        return compact;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize library wait preference.", exception);
        }
    }
}
