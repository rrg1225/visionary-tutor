package com.visionary.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.AgentTaskType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes HTTP JSON bodies into {@link AgentInvokeRequest}.
 * Top-level extension fields (e.g. facialToken) are merged into payloadExt.
 */
public final class AgentInvokeRequestSupport {

    private static final List<String> EXT_FIELD_KEYS = List.of(
            "imageBase64",
            "imageUrl",
            "ragQuery",
            "learnerQuestion",
            "diagnosisId",
            "voiceToken",
            "facialToken",
            "studentProfileSnapshot",
            "sensoryTags",
            "learningSessionId",
            "weakPointsSnapshot",
            "emotionSnapshot",
            "enableVoice"
    );

    private AgentInvokeRequestSupport() {
    }

    public static AgentInvokeRequest fromJson(JsonNode body, ObjectMapper objectMapper) {
        if (body == null || body.isNull()) {
            return new AgentInvokeRequest(null, null, null, null);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = objectMapper.convertValue(body, Map.class);
        return fromMap(raw);
    }

    public static AgentInvokeRequest fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return new AgentInvokeRequest(null, null, null, null);
        }

        AgentTaskType taskType = parseTaskType(raw.get("taskType"));
        String payloadText = stringValue(raw.get("payloadText"));
        String contextPrompt = stringValue(raw.get("contextPrompt"));

        Map<String, Object> ext = new LinkedHashMap<>();
        Object payloadExt = raw.get("payloadExt");
        if (payloadExt instanceof Map<?, ?> nested) {
            nested.forEach((key, value) -> ext.put(String.valueOf(key), value));
        }

        for (String key : EXT_FIELD_KEYS) {
            if (raw.containsKey(key) && !ext.containsKey(key)) {
                ext.put(key, raw.get(key));
            }
        }

        return new AgentInvokeRequest(
                taskType,
                payloadText,
                contextPrompt,
                ext.isEmpty() ? null : ext
        );
    }

    private static AgentTaskType parseTaskType(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return AgentTaskType.valueOf(text.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
