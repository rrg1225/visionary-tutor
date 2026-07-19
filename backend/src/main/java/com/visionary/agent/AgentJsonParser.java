package com.visionary.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class AgentJsonParser {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");
    private static final Pattern UNQUOTED_KEY = Pattern.compile("([,{]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:");

    private final ObjectMapper objectMapper;

    public AgentJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parseLenient(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Empty AI response");
        }
        String trimmed = rawText.replace("\uFEFF", "").trim();
        List<String> candidates = new ArrayList<>();
        candidates.add(trimmed);

        Matcher matcher = JSON_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            candidates.add(matcher.group(1));
        }

        String balancedObject = firstBalancedObject(trimmed);
        if (balancedObject != null) {
            candidates.add(balancedObject);
        }

        Exception lastFailure = null;
        for (String candidate : candidates) {
            try {
                JsonNode parsed = objectMapper.readTree(candidate);
                if (parsed != null && parsed.isTextual() && parsed.asText().contains("{")) {
                    return parseLenient(parsed.asText());
                }
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception ex) {
                lastFailure = ex;
            }
            try {
                JsonNode repaired = objectMapper.readTree(repairCommonModelJson(candidate));
                if (repaired != null) {
                    log.info("Recovered malformed AI JSON with deterministic repair");
                    return repaired;
                }
            } catch (Exception ex) {
                lastFailure = ex;
            }
        }
        throw new IllegalArgumentException("Unable to parse JSON from AI response", lastFailure);
    }

    private static String repairCommonModelJson(String candidate) {
        String repaired = candidate
                .replace('“', '"')
                .replace('”', '"')
                .replace('：', ':')
                .replace('，', ',')
                .replaceAll("\\bNone\\b", "null")
                .replaceAll("\\bTrue\\b", "true")
                .replaceAll("\\bFalse\\b", "false");
        repaired = UNQUOTED_KEY.matcher(repaired).replaceAll("$1\"$2\":");
        repaired = TRAILING_COMMA.matcher(repaired).replaceAll("$1");
        return repaired;
    }

    private static String firstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        return null;
    }

    public String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return defaultValue;
        }
        return value.asText();
    }

    public double number(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return defaultValue;
        }
        return value.asDouble();
    }

    public ReActDecision parseReActDecision(String rawText, Set<String> allowedActions) {
        JsonNode root = parseLenient(rawText);
        if (!root.isObject()) {
            throw new IllegalArgumentException("ReAct response must be a JSON object");
        }
        String thought = requiredText(root, "thought");
        String action = requiredText(root, "action");
        JsonNode actionInput = root.path("action_input");
        if (!actionInput.isObject()) {
            throw new IllegalArgumentException("ReAct action_input must be a JSON object");
        }
        boolean allowed = "FINISH".equalsIgnoreCase(action)
                || (allowedActions != null && allowedActions.contains(action));
        if (!allowed) {
            throw new IllegalArgumentException("ReAct action is not registered: " + action);
        }
        return new ReActDecision(thought, action, actionInput.deepCopy());
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("ReAct response missing required text field: " + field);
        }
        return value.asText().trim();
    }

    public record ReActDecision(String thought, String action, JsonNode actionInput) {
    }
}
