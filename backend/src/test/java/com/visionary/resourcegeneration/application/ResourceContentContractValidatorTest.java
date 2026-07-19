package com.visionary.resourcegeneration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContentContractValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ResourceContentContractValidator validator = new ResourceContentContractValidator(objectMapper);

    @Test
    void normalizesLegacyMetadataIntoTheVersionedEnvelope() throws Exception {
        GeneratedArtifact artifact = artifact();
        artifact.setContentJson("{\"graph\":{\"mermaid\":\"A-->B\"}}");

        JsonNode result = objectMapper.readTree(validator.normalize(artifact));

        assertEquals("1.0", result.path("schema_version").asText());
        assertEquals("LIVE", result.path("origin").asText());
        assertEquals("UNSPECIFIED", result.path("generation_mode").asText());
        assertEquals("HANDOUTAgent", result.path("agent").asText());
        assertTrue(result.path("personalized").asBoolean());
        assertFalse(result.path("degraded").asBoolean());
        assertEquals("A-->B", result.path("graph").path("mermaid").asText());
    }

    @Test
    void degradedArtifactsMustDiscloseAReasonAndMalformedJsonFailsClosed() throws Exception {
        GeneratedArtifact artifact = artifact();
        artifact.setPublishStatus("DEGRADED");
        artifact.setReviewNotes("model timeout");
        JsonNode result = objectMapper.readTree(validator.normalize(artifact));
        assertEquals("DEGRADED", result.path("origin").asText());
        assertEquals("model timeout", result.path("fallback_reason").asText());

        artifact.setContentJson("[1,2,3]");
        assertThrows(IllegalArgumentException.class, () -> validator.normalize(artifact));
    }

    @Test
    void degradedPublishStateDoesNotOverrideExplicitLiveOriginWhenEnvelopeIsNotDegraded() throws Exception {
        // PublishGate DEGRADED is a quality rating, not a signal that the model
        // was unavailable. An explicit LIVE origin set by ensureLiveGenerationMode
        // must survive normalization even when publishStatus is DEGRADED.
        GeneratedArtifact artifact = artifact();
        artifact.setPublishStatus("DEGRADED");
        artifact.setContentJson("{\"origin\":\"LIVE\",\"degraded\":false}");

        JsonNode result = objectMapper.readTree(validator.normalize(artifact));

        assertEquals("LIVE", result.path("origin").asText());
        assertFalse(result.path("degraded").asBoolean());
    }

    @Test
    void degradedPublishStateOverridesWhenEnvelopeIsAlreadyDegraded() throws Exception {
        // When the envelope itself is already marked degraded (by
        // markGenerationMode), normalize must preserve that state regardless
        // of what publishStatus says.
        GeneratedArtifact artifact = artifact();
        artifact.setPublishStatus("PUBLISHED");
        artifact.setContentJson("{\"origin\":\"DEGRADED\",\"degraded\":true}");

        JsonNode result = objectMapper.readTree(validator.normalize(artifact));

        assertEquals("DEGRADED", result.path("origin").asText());
        assertTrue(result.path("degraded").asBoolean());
    }

    @Test
    void publishGateDegradedWithoutEnvelopeDegradedRespectsExplicitLiveOrigin() throws Exception {
        // Simulates the real bug scenario: PublishGate returned DEGRADED
        // (medium faithfulness), but ensureLiveGenerationMode already
        // stamped LIVE — normalize must not downgrade.
        GeneratedArtifact artifact = artifact();
        artifact.setPublishStatus("DEGRADED");
        artifact.setReviewNotes("CriticReport{verdict=PASS, factualityScore=0.96}");
        artifact.setContentJson(
                "{\"origin\":\"LIVE\",\"degraded\":false,\"generation_mode\":\"REACT_MULTI_AGENT\",\"agent\":\"DocAgent\"}");

        JsonNode result = objectMapper.readTree(validator.normalize(artifact));

        assertEquals("LIVE", result.path("origin").asText());
        assertFalse(result.path("degraded").asBoolean());
        assertEquals("REACT_MULTI_AGENT", result.path("generation_mode").asText());
        // No fallback_reason should be injected for a live artifact
        assertTrue(result.path("fallback_reason").isMissingNode()
                || result.path("fallback_reason").asText().isEmpty());
    }

    private static GeneratedArtifact artifact() {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setRunId("live-run");
        artifact.setArtifactType(GeneratedArtifact.ArtifactType.HANDOUT);
        artifact.setPublishStatus("PUBLISHED");
        return artifact;
    }
}
