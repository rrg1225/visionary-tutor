package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.visionary.entity.GeneratedArtifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationFallbackService {

    private final ObjectMapper objectMapper;

    public void markGenerationMode(
            GeneratedArtifact artifact,
            String mode,
            String reason,
            String agentName,
            String summary,
            String controlledMediaPrompt
    ) {
        if (artifact == null) {
            return;
        }
        artifact.setPublishStatus("DEGRADED");
        artifact.setReviewNotes(firstNonBlank(artifact.getReviewNotes(), "")
                + " | [GenerationMode] " + mode + ": " + firstNonBlank(reason, "unspecified"));

        Map<String, Object> payload = readPayload(artifact.getContentJson());
        putCommonFields(payload, artifact, mode, "DEGRADED", true, agentName);
        payload.putIfAbsent("summary", firstNonBlank(summary, ""));
        payload.put("fallback_reason", firstNonBlank(reason, "unspecified"));
        if (controlledMediaPrompt != null && !controlledMediaPrompt.isBlank()) {
            payload.put("controlledMediaPrompt", controlledMediaPrompt);
        }
        artifact.setContentJson(toJson(payload));
    }

    public void ensureLiveGenerationMode(GeneratedArtifact artifact, String mode, String agentName) {
        if (artifact == null) {
            return;
        }
        Map<String, Object> payload = readPayload(artifact.getContentJson());
        payload.putIfAbsent("type", artifact.getArtifactType() != null
                ? artifact.getArtifactType().name() : "UNKNOWN");
        payload.putIfAbsent("agent", firstNonBlank(agentName, "UnknownAgent"));
        // For generation_mode: overwrite UNSPECIFIED / blank / null values so
        // that the live mode resolution takes effect, but preserve explicitly
        // specified specialist modes (e.g. SPECIALIST_TOOL) set by the caller.
        String existingMode = payload.get("generation_mode") instanceof String
                ? (String) payload.get("generation_mode") : null;
        String resolvedMode = firstNonBlank(mode, "UNKNOWN");
        if (existingMode == null || existingMode.isBlank()
                || "UNSPECIFIED".equalsIgnoreCase(existingMode)) {
            payload.put("generation_mode", resolvedMode);
        } else {
            payload.putIfAbsent("generation_mode", resolvedMode);
        }
        // Use put (not putIfAbsent) for origin and degraded so that a prior
        // normalize() pass cannot lock these fields to stale values. When the
        // orchestrator decides this is a LIVE path, the content envelope must
        // reflect that regardless of intermediate persistence steps.
        payload.put("origin", "LIVE");
        payload.put("degraded", false);
        // Clear any stale fallback_reason — this is a live generation path, not
        // a degraded fallback. A prior normalize() may have stamped a reason
        // based on publish status alone, but that is a quality rating, not a
        // signal that the model was unavailable.
        payload.remove("fallback_reason");
        payload.putIfAbsent("personalized", true);
        artifact.setContentJson(toJson(payload));
    }

    public void markDemoGeneration(GeneratedArtifact artifact, String agentName) {
        if (artifact == null) {
            return;
        }
        Map<String, Object> payload = readPayload(artifact.getContentJson());
        putCommonFields(payload, artifact, "DEMO_MODE", "DEMO", false, agentName);
        payload.put("personalized", false);
        artifact.setContentJson(toJson(payload));
    }

    private void putCommonFields(
            Map<String, Object> payload,
            GeneratedArtifact artifact,
            String mode,
            String origin,
            boolean degraded,
            String agentName
    ) {
        payload.putIfAbsent("type", artifact.getArtifactType() != null
                ? artifact.getArtifactType().name() : "UNKNOWN");
        payload.put("agent", firstNonBlank(agentName, "UnknownAgent"));
        payload.put("generation_mode", firstNonBlank(mode, "UNKNOWN"));
        payload.put("origin", origin);
        payload.put("degraded", degraded);
        payload.putIfAbsent("personalized", !"DEMO".equals(origin));
    }

    private Map<String, Object> readPayload(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(
                    contentJson,
                    new TypeReference<Map<String, Object>>() { }
            ));
        } catch (Exception ex) {
            log.warn("Existing artifact metadata is not a JSON object; provenance will start fresh: {}", ex.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Fallback metadata serialization failed: {}", ex.getMessage());
            return "{}";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
