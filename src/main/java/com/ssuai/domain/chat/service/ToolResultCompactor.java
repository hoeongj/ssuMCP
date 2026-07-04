package com.ssuai.domain.chat.service;

import java.util.Iterator;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ssuai.connector.chat", havingValue = "llm")
public class ToolResultCompactor {

    private static final int MAX_CHAT_TOOL_FACILITY_RESULTS = 10;
    private static final int MAX_CHAT_TOOL_LIST_RESULTS = 20;
    private static final int MAX_NOTICE_DETAIL_CHARS = 2_000;
    // Char cap (compared against String.length(), i.e. UTF-16 units, not bytes).
    private static final int MAX_TOOL_CONTENT_CHARS = 8 * 1024;
    private static final String TOOL_TRUNCATION_MARKER = "...[truncated]";

    private final ObjectMapper objectMapper;

    public ToolResultCompactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String compactAndCap(String toolName, String rawJsonText) {
        try {
            JsonNode tree = objectMapper.readTree(rawJsonText);
            JsonNode compacted = switch (toolName) {
                case "get_today_meal", "get_meal_by_date" -> compactMealNode(tree);
                case "get_dorm_weekly_meal" -> compactWeeklyMealNode(tree);
                case "search_campus_facilities" -> compactFacilityListNode(tree);
                case "get_library_seat_status" -> compactLibrarySeatNode(tree);
                case "search_library_book" -> compactLibraryBookSearchNode(tree);
                case "get_my_schedule" -> compactScheduleNode(tree);
                case "get_my_grades" -> compactGradesNode(tree);
                case "get_my_chapel_info" -> compactChapelNode(tree);
                case "check_graduation_requirements" -> compactGraduationNode(tree);
                case "get_my_scholarships" -> compactScholarshipsNode(tree);
                case "get_my_assignments" -> compactAssignmentsNode(tree);
                case "get_my_library_loans" -> compactLoansNode(tree);
                case "get_recent_notices", "search_notices", "get_active_notices",
                     "get_department_notices" -> compactNoticeListNode(tree);
                case "get_notice_detail" -> compactNoticeDetailNode(tree);
                case "list_notice_categories" -> tree;
                default -> tree;
            };
            return capLength(objectMapper.writeValueAsString(compacted));
        } catch (JsonProcessingException exception) {
            return capLength(rawJsonText);
        }
    }

    private static String capLength(String value) {
        if (value.length() <= MAX_TOOL_CONTENT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_TOOL_CONTENT_CHARS) + TOOL_TRUNCATION_MARKER;
    }

    private ObjectNode compactMealNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "date");
        compact.set("meals", filterArray(node.get("meals"), this::compactMealItemNode));
        JsonNode closures = node.get("closures");
        if (closures != null && closures.isArray() && !closures.isEmpty()) {
            compact.set("closures", filterArray(closures, this::compactClosureNode));
        }
        return compact;
    }

    private ObjectNode compactWeeklyMealNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "startDate");
        copyTextIfPresent(node, compact, "endDate");
        compact.set("days", filterArray(node.get("days"), this::compactMealNode));
        return compact;
    }

    private ObjectNode compactFacilityListNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        JsonNode facilities = node.get("facilities");
        int total = facilities != null && facilities.isArray() ? facilities.size() : 0;
        compact.put("resultCount", total);
        compact.put("truncated", total > MAX_CHAT_TOOL_FACILITY_RESULTS);
        ArrayNode trimmed = objectMapper.createArrayNode();
        if (facilities != null && facilities.isArray()) {
            int limit = Math.min(total, MAX_CHAT_TOOL_FACILITY_RESULTS);
            for (int index = 0; index < limit; index++) {
                trimmed.add(compactFacilityNode(facilities.get(index)));
            }
        }
        compact.set("facilities", trimmed);
        return compact;
    }

    private ObjectNode compactMealItemNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "restaurant");
        copyTextIfPresent(node, compact, "type");
        copyTextIfPresent(node, compact, "corner");
        if (node.hasNonNull("menu")) {
            compact.set("menu", node.get("menu"));
        }
        return compact;
    }

    private ObjectNode compactClosureNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "restaurant");
        copyTextIfPresent(node, compact, "reason");
        return compact;
    }

    private ObjectNode compactLibrarySeatNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "floor");
        copyTextIfPresent(node, compact, "floorLabel");
        copyIntIfPresent(node, compact, "totalSeats");
        copyIntIfPresent(node, compact, "availableSeats");
        copyIntIfPresent(node, compact, "reservedSeats");
        copyIntIfPresent(node, compact, "outOfServiceSeats");
        JsonNode zones = node.get("zones");
        if (zones != null && zones.isArray() && !zones.isEmpty()) {
            compact.set("zones", filterArray(zones, this::compactLibrarySeatZoneNode));
        }
        return compact;
    }

    private ObjectNode compactLibrarySeatZoneNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "label");
        copyIntIfPresent(node, compact, "total");
        copyIntIfPresent(node, compact, "available");
        return compact;
    }

    /**
     * Schedule entries are grouped by course, with only compact meeting
     * coordinates retained for prompt budget control.
     */
    private ObjectNode compactScheduleNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "enrollmentYear");
        copyIntIfPresent(node, compact, "currentYear");
        copyIntIfPresent(node, compact, "currentTerm");
        JsonNode terms = node.get("terms");
        if (terms != null && terms.isArray()) {
            compact.set("terms", filterArray(terms, this::compactTermScheduleNode));
        }
        return compact;
    }

    private ObjectNode compactTermScheduleNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "year");
        copyIntIfPresent(node, compact, "term");
        JsonNode entries = node.get("entries");
        if (entries != null && entries.isArray()) {
            compact.set("entries", filterArray(entries, this::compactScheduleEntryNode));
        }
        return compact;
    }

    private ObjectNode compactScheduleEntryNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "course");
        copyTextIfPresent(node, compact, "professor");
        JsonNode meetings = node.get("meetings");
        if (meetings != null && meetings.isArray()) {
            compact.set("meetings", filterArray(meetings, this::compactMeetingSlotNode));
        }
        return compact;
    }

    private ObjectNode compactMeetingSlotNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "dayOfWeek");
        copyIntIfPresent(node, compact, "period");
        copyTextIfPresent(node, compact, "room");
        return compact;
    }

    /**
     * Passes cumulative GPA and per-term GPA history to the LLM so it can
     * answer "내 학점 평균이 뭐야" type questions directly. Per-course data
     * (course names, scores, letter grades, professor names) is still
     * excluded — those require the /grades page.
     */
    private ObjectNode compactGradesNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("count", countGradeCourses(node));
        compact.put("link", "/grades");

        JsonNode academic = node.path("academicRecord");
        if (!academic.isMissingNode() && !academic.isNull()) {
            ObjectNode gpaNode = objectMapper.createObjectNode();
            copyDoubleIfPresent(academic, gpaNode, "gpa");
            copyDoubleIfPresent(academic, gpaNode, "earnedCredits");
            copyDoubleIfPresent(academic, gpaNode, "gpaCredits");
            compact.set("academicRecord", gpaNode);
        }

        JsonNode history = node.path("history");
        if (history.isArray() && !history.isEmpty()) {
            ArrayNode compactHistory = objectMapper.createArrayNode();
            int limit = Math.min(history.size(), 12);
            for (int i = 0; i < limit; i++) {
                JsonNode term = history.get(i);
                ObjectNode termNode = objectMapper.createObjectNode();
                copyIntIfPresent(term, termNode, "year");
                copyTextIfPresent(term, termNode, "term");
                copyDoubleIfPresent(term, termNode, "gpa");
                copyDoubleIfPresent(term, termNode, "gpaCredits");
                compactHistory.add(termNode);
            }
            compact.set("history", compactHistory);
        }

        return compact;
    }

    private ObjectNode compactChapelNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "year");
        copyTextIfPresent(node, compact, "semester");
        copyIntIfPresent(node, compact, "absenceAllowedMinutes");
        copyIntIfPresent(node, compact, "absenceUsedMinutes");
        copyTextIfPresent(node, compact, "result");
        return compact;
    }

    private ObjectNode compactGraduationNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyBooleanIfPresent(node, compact, "isGraduatable");
        ArrayNode unmetItems = objectMapper.createArrayNode();
        int unmetCount = 0;
        JsonNode requirements = node.get("requirements");
        if (requirements != null && requirements.isArray()) {
            for (JsonNode req : requirements) {
                if (!req.path("satisfied").asBoolean(true)) {
                    unmetCount++;
                    ObjectNode item = objectMapper.createObjectNode();
                    copyTextIfPresent(req, item, "name");
                    // Keep progress numbers so the answer can say "89 credits done, 44 remaining".
                    copyDoubleIfPresent(req, item, "required");
                    copyDoubleIfPresent(req, item, "completed");
                    copyDoubleIfPresent(req, item, "remaining");
                    copyTextIfPresent(req, item, "requirementType");
                    unmetItems.add(item);
                }
            }
        }
        compact.put("unmetCount", unmetCount);
        compact.set("unmet", unmetItems);
        return compact;
    }

    private ArrayNode compactScholarshipsNode(JsonNode node) {
        ArrayNode compact = objectMapper.createArrayNode();
        if (node == null || !node.isArray()) {
            return compact;
        }
        int limit = Math.min(node.size(), MAX_CHAT_TOOL_LIST_RESULTS);
        for (int index = 0; index < limit; index++) {
            JsonNode item = node.get(index);
            ObjectNode entry = objectMapper.createObjectNode();
            copyTextIfPresent(item, entry, "name");
            copyIntIfPresent(item, entry, "year");
            copyTextIfPresent(item, entry, "semester");
            copyLongIfPresent(item, entry, "receivedAmount");
            compact.add(entry);
        }
        return compact;
    }

    private static int countGradeCourses(JsonNode node) {
        if (node == null) {
            return 0;
        }
        JsonNode detailsByTerm = node.get("detailsByTerm");
        if (detailsByTerm == null || !detailsByTerm.isObject()) {
            return 0;
        }
        int total = 0;
        for (Iterator<JsonNode> iterator = detailsByTerm.elements(); iterator.hasNext(); ) {
            JsonNode rows = iterator.next();
            if (rows != null && rows.isArray()) {
                total += rows.size();
            }
        }
        return total;
    }

    private ObjectNode compactAssignmentsNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("termId", node.path("termId").asLong());
        JsonNode items = node.path("items");
        if (items.isArray()) {
            ArrayNode compacted = objectMapper.createArrayNode();
            for (JsonNode item : items) {
                ObjectNode ci = objectMapper.createObjectNode();
                copyTextIfPresent(item, ci, "courseName");
                copyTextIfPresent(item, ci, "title");
                copyTextIfPresent(item, ci, "type");
                if (item.hasNonNull("dueDate")) {
                    copyTextIfPresent(item, ci, "dueDate");
                }
                compacted.add(ci);
            }
            compact.set("items", compacted);
        }
        return compact;
    }

    private ObjectNode compactLoansNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("total", node.path("total").asInt(0));
        JsonNode loans = node.path("loans");
        if (loans.isArray()) {
            compact.set("loans", filterArray(loans, this::compactLoanItemNode));
        }
        return compact;
    }

    private ObjectNode compactNoticeListNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "currentPage");
        copyIntIfPresent(node, compact, "totalPages");
        ArrayNode items = objectMapper.createArrayNode();
        JsonNode sourceItems = node.get("items");
        if (sourceItems != null && sourceItems.isArray()) {
            int limit = Math.min(sourceItems.size(), MAX_CHAT_TOOL_LIST_RESULTS);
            for (int index = 0; index < limit; index++) {
                JsonNode item = sourceItems.get(index);
                ObjectNode notice = objectMapper.createObjectNode();
                copyTextIfPresent(item, notice, "title");
                copyTextIfPresent(item, notice, "category");
                copyTextIfPresent(item, notice, "date");
                copyTextIfPresent(item, notice, "link");
                copyTextIfPresent(item, notice, "department");
                items.add(notice);
            }
        }
        compact.set("items", items);
        return compact;
    }

    private ObjectNode compactNoticeDetailNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "title");
        JsonNode bodyText = node.get("bodyText");
        if (bodyText != null && !bodyText.isNull() && !bodyText.asText("").isBlank()) {
            String text = bodyText.asText();
            compact.put("bodyText", text.length() <= MAX_NOTICE_DETAIL_CHARS
                    ? text
                    : text.substring(0, MAX_NOTICE_DETAIL_CHARS) + "...");
        }
        return compact;
    }

    private ObjectNode compactLoanItemNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "title");
        copyTextIfPresent(node, compact, "dueDate");
        JsonNode overdue = node.get("isOverdue");
        if (overdue != null && !overdue.isNull()) {
            compact.put("isOverdue", overdue.asBoolean(false));
        }
        JsonNode renewable = node.get("isRenewable");
        if (renewable != null && !renewable.isNull()) {
            compact.put("isRenewable", renewable.asBoolean(false));
        }
        return compact;
    }

    private ObjectNode compactLibraryBookSearchNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyIntIfPresent(node, compact, "total");
        copyIntIfPresent(node, compact, "page");
        copyIntIfPresent(node, compact, "size");
        JsonNode items = node.get("items");
        if (items != null && items.isArray() && !items.isEmpty()) {
            compact.set("items", filterArray(items, this::compactLibraryBookItemNode));
        }
        return compact;
    }

    private ObjectNode compactLibraryBookItemNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "title");
        copyTextIfPresent(node, compact, "author");
        copyTextIfPresent(node, compact, "publication");
        copyTextIfPresent(node, compact, "callNumber");
        copyTextIfPresent(node, compact, "location");
        copyTextIfPresent(node, compact, "status");
        return compact;
    }

    private ObjectNode compactFacilityNode(JsonNode node) {
        ObjectNode compact = objectMapper.createObjectNode();
        copyTextIfPresent(node, compact, "name");
        if (node.hasNonNull("categoryLabel")) {
            compact.put("category", node.get("categoryLabel").asText());
        } else if (node.hasNonNull("category")) {
            compact.put("category", node.get("category").asText());
        }
        copyTextIfPresent(node, compact, "location");
        copyTextIfPresent(node, compact, "phone");
        copyTextIfPresent(node, compact, "extension");
        copyNonEmptyArray(node, compact, "weekdayHours");
        copyNonEmptyArray(node, compact, "weekendHours");
        copyNonEmptyArray(node, compact, "notes");
        return compact;
    }

    private static void copyTextIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && !value.asText("").isBlank()) {
            target.put(field, value.asText());
        }
    }

    private static void copyIntIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && value.isNumber()) {
            target.put(field, value.asInt());
        }
    }

    private static void copyLongIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && value.isNumber()) {
            target.put(field, value.asLong());
        }
    }

    private static void copyBooleanIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && value.isBoolean()) {
            target.put(field, value.asBoolean());
        }
    }

    private static void copyDoubleIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull() && value.isNumber()) {
            target.put(field, value.asDouble());
        }
    }

    private static void copyNonEmptyArray(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isArray() && !value.isEmpty()) {
            target.set(field, value);
        }
    }

    private ArrayNode filterArray(JsonNode source, Function<JsonNode, ObjectNode> mapper) {
        ArrayNode array = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return array;
        }
        for (Iterator<JsonNode> iterator = source.elements(); iterator.hasNext(); ) {
            array.add(mapper.apply(iterator.next()));
        }
        return array;
    }
}
