package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationFallbackServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GenerationFallbackService service = new GenerationFallbackService(objectMapper);

    @Test
    void fallbackMetadataPreservesExistingVideoPlan() throws Exception {
        GeneratedArtifact artifact = artifact();
        artifact.setContentJson("{\"video_plan\":{\"segment_count\":3},\"summary\":\"existing\"}");

        service.markGenerationMode(
                artifact,
                "LEGACY_FALLBACK",
                "ReAct timed out",
                "StoryboardAgent",
                "new summary",
                "controlled prompt"
        );

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals(3, metadata.path("video_plan").path("segment_count").asInt());
        assertEquals("existing", metadata.path("summary").asText());
        assertEquals("DEGRADED", metadata.path("origin").asText());
        assertTrue(metadata.path("degraded").asBoolean());
        assertEquals("ReAct timed out", metadata.path("fallback_reason").asText());
        assertEquals("DEGRADED", artifact.getPublishStatus());
    }

    @Test
    void liveModeDoesNotOverwriteSpecialistMode() throws Exception {
        GeneratedArtifact artifact = artifact();
        artifact.setContentJson("{\"generation_mode\":\"SPECIALIST_TOOL\",\"agent\":\"DocAgent\"}");

        service.ensureLiveGenerationMode(artifact, "REACT_MULTI_AGENT", "LectureAgent");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("SPECIALIST_TOOL", metadata.path("generation_mode").asText());
        assertEquals("DocAgent", metadata.path("agent").asText());
        assertEquals("LIVE", metadata.path("origin").asText());
    }

    @Test
    void liveModeOverwritesUnspecifiedGenerationMode() throws Exception {
        // When the existing generation_mode is UNSPECIFIED (from a prior
        // normalize pass), ensureLiveGenerationMode must overwrite it
        // with the resolved live mode.
        GeneratedArtifact artifact = artifact();
        artifact.setContentJson(
                "{\"generation_mode\":\"UNSPECIFIED\",\"agent\":\"UnknownAgent\",\"origin\":\"LIVE\"}");

        service.ensureLiveGenerationMode(artifact, "REACT_MULTI_AGENT", "DocAgent");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("REACT_MULTI_AGENT", metadata.path("generation_mode").asText());
        assertEquals("LIVE", metadata.path("origin").asText());
        assertFalse(metadata.path("degraded").asBoolean());
    }

    @Test
    void liveModeClearsStaleFallbackReason() throws Exception {
        GeneratedArtifact artifact = artifact();
        artifact.setContentJson(
                "{\"generation_mode\":\"UNSPECIFIED\",\"fallback_reason\":\"Grounded content accepted | CriticReport{verdict=PASS}\"}");

        service.ensureLiveGenerationMode(artifact, "REACT_MULTI_AGENT", "LectureAgent");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("REACT_MULTI_AGENT", metadata.path("generation_mode").asText());
        assertFalse(metadata.path("degraded").asBoolean());
        assertEquals("LIVE", metadata.path("origin").asText());
        // fallback_reason must be removed for a live path
        assertTrue(metadata.path("fallback_reason").isMissingNode()
                || metadata.path("fallback_reason").asText().isEmpty());
    }

    @Test
    void liveModeOverwritesBlankGenerationMode() throws Exception {
        GeneratedArtifact artifact = artifact();
        artifact.setContentJson("{\"generation_mode\":\"\",\"agent\":\"UnknownAgent\"}");

        service.ensureLiveGenerationMode(artifact, "REACT_MULTI_AGENT", "DocAgent");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("REACT_MULTI_AGENT", metadata.path("generation_mode").asText());
    }

    @Test
    void demoModeIsExplicitlyNonPersonalized() throws Exception {
        GeneratedArtifact artifact = artifact();

        service.markDemoGeneration(artifact, "LocalMockService");

        JsonNode metadata = objectMapper.readTree(artifact.getContentJson());
        assertEquals("DEMO", metadata.path("origin").asText());
        assertEquals("DEMO_MODE", metadata.path("generation_mode").asText());
        assertFalse(metadata.path("personalized").asBoolean(true));
        assertFalse(metadata.path("degraded").asBoolean(true));
    }

    private GeneratedArtifact artifact() {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setArtifactType(GeneratedArtifact.ArtifactType.VISUALIZATION);
        artifact.setReviewNotes("");
        return artifact;
    }
}
