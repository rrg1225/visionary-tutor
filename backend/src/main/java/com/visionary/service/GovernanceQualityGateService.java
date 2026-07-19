package com.visionary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceQualityGateService {

    private final ContentSafetyFilter contentSafetyFilter;
    private final ObjectMapper objectMapper;

    public SafetyReviewResult applyArtifactSafetyMetadata(
            GeneratedArtifact artifact,
            double factuality,
            String agentName,
            String summary
    ) {
        if (artifact == null) {
            return new SafetyReviewResult(true, "PASSED", "Artifact is empty; skipped safety review");
        }
        String text = artifact.getContentMarkdown() != null ? artifact.getContentMarkdown() : "";
        String citationStatus = artifact.getValidationStatus() != null
                ? artifact.getValidationStatus()
                : "UNVERIFIED";

        ContentSafetyFilter.SafetyResult safety = contentSafetyFilter.check(text, factuality, citationStatus);
        String safetyStatus = safety.passed() ? "PASSED" : safety.status();
        String academicReview = safety.passed()
                ? "PASSED"
                : ("REJECTED".equals(safetyStatus) ? "REJECTED" : "NEEDS_REVIEW");

        Map<String, Object> payload = readExistingMetadata(artifact);
        payload.put("type", artifact.getArtifactType() != null ? artifact.getArtifactType().name() : "UNKNOWN");
        payload.put("agent", agentName);
        payload.put("summary", summary);
        payload.put("content_safety", safetyStatus);
        payload.put("factuality_score", factuality);
        payload.put("quality_gate", safety.passed() ? "PASSED" : safetyStatus);
        payload.put("safety_flags", Map.of(
                "citation", citationStatus,
                "factuality", String.format("%.2f", factuality),
                "content_safety", safetyStatus,
                "academic_review", academicReview
        ));
        artifact.setContentJson(toJson(payload));

        if (!safety.passed()) {
            boolean rejected = "REJECTED".equals(safety.status());
            artifact.setPublishStatus(rejected ? "BLOCKED" : "DEGRADED");
            artifact.setValidationStatus("NEEDS_HUMAN_REVIEW");
            payload.put("origin", "DEGRADED");
            payload.put("degraded", true);
            payload.put("fallback_reason", safety.message());
            artifact.setContentJson(toJson(payload));
            artifact.setReviewNotes(appendReviewNote(
                    artifact.getReviewNotes(),
                    rejected
                            ? "[ContentSafetyFilter] 已拦截：" + safety.message()
                            : "[ContentSafetyFilter] 需返修：" + safety.message()
            ));
        }

        return new SafetyReviewResult(safety.passed(), safety.status(), safety.message());
    }

    public record SafetyReviewResult(boolean passed, String status, String message) {
    }

    private Map<String, Object> readExistingMetadata(GeneratedArtifact artifact) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (artifact.getContentJson() == null || artifact.getContentJson().isBlank()) {
            return payload;
        }
        try {
            var root = objectMapper.readTree(artifact.getContentJson());
            if (root != null && root.isObject()) {
                payload.putAll(objectMapper.convertValue(
                        root,
                        new TypeReference<Map<String, Object>>() { }
                ));
            }
        } catch (Exception ex) {
            log.warn("Failed to preserve existing artifact metadata: {}", ex.getMessage());
        }
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Safety metadata serialization failed: {}", ex.getMessage());
            return "{}";
        }
    }

    private static String appendReviewNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        if (existing.contains(addition)) {
            return existing;
        }
        return existing + " | " + addition;
    }
}
