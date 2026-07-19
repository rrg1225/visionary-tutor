package com.visionary.resourcegeneration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.entity.GeneratedArtifact;
import org.springframework.stereotype.Service;

/** Normalizes and validates the versioned contentJson envelope before persistence. */
@Service
public class ResourceContentContractValidator {

    public static final String SCHEMA_VERSION = "1.0";

    private final ObjectMapper objectMapper;

    public ResourceContentContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String normalize(GeneratedArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("Artifact is required");
        }
        ObjectNode envelope = parseObject(artifact.getContentJson());
        boolean publishGateDegraded = "DEGRADED".equalsIgnoreCase(artifact.getPublishStatus());
        boolean envelopeDegraded = envelope.path("degraded").asBoolean(false);
        String origin = text(envelope, "origin");
        boolean originWasExplicitlySet = isOrigin(origin);

        boolean degraded;
        if (originWasExplicitlySet) {
            // Caller explicitly set origin (e.g. ensureLiveGenerationMode already
            // stamped LIVE). Trust the explicit origin; PublishGate DEGRADED is a
            // quality rating, not a signal that the model was unavailable. Do not
            // downgrade a live-generated artifact based on publish status alone.
            degraded = envelopeDegraded;
            // origin stays as explicitly set by the caller
        } else if (envelopeDegraded || publishGateDegraded) {
            degraded = true;
            origin = "DEGRADED";
        } else {
            degraded = false;
            origin = inferDemo(artifact) ? "DEMO" : "LIVE";
        }
        envelope.put("schema_version", SCHEMA_VERSION);
        envelope.put("origin", origin);
        envelope.put("generation_mode", nonBlank(text(envelope, "generation_mode"), "UNSPECIFIED"));
        envelope.put("degraded", degraded || "DEGRADED".equals(origin));
        envelope.put("personalized", envelope.path("personalized").asBoolean(true));
        envelope.put("agent", nonBlank(text(envelope, "agent"), inferAgent(artifact)));
        if (envelope.path("degraded").asBoolean() && text(envelope, "fallback_reason").isBlank()) {
            envelope.put("fallback_reason", nonBlank(artifact.getReviewNotes(), "Controlled fallback"));
        }
        validate(envelope);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Resource content envelope cannot be serialized", exception);
        }
    }

    private ObjectNode parseObject(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(contentJson);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("contentJson must be a JSON object");
            }
            return (ObjectNode) parsed.deepCopy();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("contentJson is not valid JSON", exception);
        }
    }

    private void validate(ObjectNode envelope) {
        if (!SCHEMA_VERSION.equals(text(envelope, "schema_version"))) {
            throw new IllegalArgumentException("Unsupported resource content schema version");
        }
        if (!isOrigin(text(envelope, "origin"))) {
            throw new IllegalArgumentException("Invalid resource origin");
        }
        for (String field : new String[]{"generation_mode", "agent"}) {
            if (text(envelope, field).isBlank()) {
                throw new IllegalArgumentException("Missing resource content field: " + field);
            }
        }
        for (String field : new String[]{"degraded", "personalized"}) {
            if (!envelope.path(field).isBoolean()) {
                throw new IllegalArgumentException("Resource content field must be boolean: " + field);
            }
        }
    }

    private static boolean inferDemo(GeneratedArtifact artifact) {
        return artifact.getRunId() != null && artifact.getRunId().toUpperCase().contains("DEMO");
    }

    private static String inferAgent(GeneratedArtifact artifact) {
        return artifact.getArtifactType() == null ? "UnknownAgent" : artifact.getArtifactType().name() + "Agent";
    }

    private static boolean isOrigin(String origin) {
        return "LIVE".equals(origin) || "DEGRADED".equals(origin) || "DEMO".equals(origin);
    }

    private static String text(ObjectNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText().trim() : "";
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
