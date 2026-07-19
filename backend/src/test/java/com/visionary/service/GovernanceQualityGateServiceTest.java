package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceQualityGateServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContentSafetyFilter contentSafetyFilter = mock(ContentSafetyFilter.class);
    private final GovernanceQualityGateService service =
            new GovernanceQualityGateService(contentSafetyFilter, objectMapper);

    @Test
    void safetyReviewEnrichesMetadataWithoutErasingProvenanceOrExtensions() throws Exception {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setArtifactType(GeneratedArtifact.ArtifactType.LEARNING_PATH);
        artifact.setContentMarkdown("Grounded learning path");
        artifact.setValidationStatus("VALID");
        artifact.setContentJson("""
                {
                  "schema_version":"1.0",
                  "origin":"LIVE",
                  "generation_mode":"REACT_MULTI_AGENT",
                  "degraded":false,
                  "personalized":true,
                  "graph":{"mermaid":"A-->B"},
                  "custom_extension":{"retained":true}
                }
                """);
        when(contentSafetyFilter.check("Grounded learning path", 0.96, "VALID"))
                .thenReturn(new ContentSafetyFilter.SafetyResult(true, "PASSED", "safe", 0.0));

        service.applyArtifactSafetyMetadata(artifact, 0.96, "PathAgent", "updated summary");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("1.0", metadata.path("schema_version").asText());
        assertEquals("LIVE", metadata.path("origin").asText());
        assertEquals("REACT_MULTI_AGENT", metadata.path("generation_mode").asText());
        assertFalse(metadata.path("degraded").asBoolean(true));
        assertTrue(metadata.path("personalized").asBoolean(false));
        assertEquals("A-->B", metadata.path("graph").path("mermaid").asText());
        assertTrue(metadata.path("custom_extension").path("retained").asBoolean());
        assertEquals("PASSED", metadata.path("safety_flags").path("content_safety").asText());
        assertEquals("PASSED", metadata.path("content_safety").asText());
        assertEquals(0.96, metadata.path("factuality_score").asDouble(), 0.001);
        assertEquals("PathAgent", metadata.path("agent").asText());
        assertEquals("updated summary", metadata.path("summary").asText());
    }

    @Test
    void failedSafetyReviewCannotRemainPublishedOrLive() throws Exception {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setArtifactType(GeneratedArtifact.ArtifactType.HANDOUT);
        artifact.setContentMarkdown("unsupported claim");
        artifact.setValidationStatus("GROUNDED");
        artifact.setPublishStatus("PUBLISHED");
        artifact.setContentJson("{\"origin\":\"LIVE\",\"degraded\":false}");
        when(contentSafetyFilter.check("unsupported claim", 0.0, "GROUNDED"))
                .thenReturn(new ContentSafetyFilter.SafetyResult(false, "REJECTED", "factuality too low", 0.8));

        service.applyArtifactSafetyMetadata(artifact, 0.0, "DocAgent", "summary");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("BLOCKED", artifact.getPublishStatus());
        assertEquals("NEEDS_HUMAN_REVIEW", artifact.getValidationStatus());
        assertEquals("DEGRADED", metadata.path("origin").asText());
        assertTrue(metadata.path("degraded").asBoolean());
        assertEquals("REJECTED", metadata.path("content_safety").asText());
    }
}
