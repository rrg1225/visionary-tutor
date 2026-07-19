package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import com.visionary.resourcegeneration.domain.GenerationState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenerationContractsTest {

    @Test
    void commandDefensivelyCopiesRequestedResourceTypes() {
        List<ArtifactType> mutableTypes = new ArrayList<>();
        mutableTypes.add(ArtifactType.HANDOUT);
        GenerationCommand command = new GenerationCommand(7L, "CNN", "{}", "padding", "focused", mutableTypes);

        mutableTypes.add(ArtifactType.QUIZ);

        assertEquals(List.of(ArtifactType.HANDOUT), command.resourceTypes());
        assertThrows(UnsupportedOperationException.class,
                () -> command.resourceTypes().add(ArtifactType.MINDMAP));
    }

    @Test
    void commandNormalizesMissingResourceTypes() {
        GenerationCommand command = new GenerationCommand(7L, "CNN", "{}", null, null, null);
        assertEquals(List.of(), command.resourceTypes());
        assertEquals(7L, command.learningSessionId());
        assertEquals("CNN", command.topic());
    }

    @Test
    void resultCarriesResponseStateAndModeAcrossThePort() {
        ResourceGenerationResponse response = new ResourceGenerationResponse("trace", List.of(), List.of(), "ok");
        GenerationResult result = new GenerationResult(response, GenerationState.SUCCEEDED, "react");

        assertEquals(response, result.response());
        assertEquals(GenerationState.SUCCEEDED, result.finalState());
        assertEquals("react", result.orchestrationMode());
    }

    @Test
    void classifierNeverReportsBlockedOrUnverifiedArtifactsAsSuccessful() {
        GeneratedArtifact blocked = artifact("BLOCKED", "INVALID_CITATION");
        GeneratedArtifact unverified = artifact("PUBLISHED", "UNVERIFIED");
        GeneratedArtifact grounded = artifact("PUBLISHED", "GROUNDED");
        GeneratedArtifact modelOnly = artifact("PUBLISHED", "NO_EVIDENCE");

        assertEquals(GenerationState.MANUAL_REVIEW, GenerationResultClassifier.classify(response(blocked)));
        assertEquals(GenerationState.DEGRADED, GenerationResultClassifier.classify(response(unverified)));
        assertEquals(GenerationState.DEGRADED, GenerationResultClassifier.classify(response(grounded, blocked)));
        assertEquals(GenerationState.SUCCEEDED, GenerationResultClassifier.classify(response(grounded)));
        assertEquals(GenerationState.SUCCEEDED, GenerationResultClassifier.classify(response(modelOnly)));
    }

    private static ResourceGenerationResponse response(GeneratedArtifact... artifacts) {
        return new ResourceGenerationResponse("trace", List.of(artifacts), List.of(), "review");
    }

    private static GeneratedArtifact artifact(String publishStatus, String validationStatus) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setPublishStatus(publishStatus);
        artifact.setValidationStatus(validationStatus);
        return artifact;
    }

    @Test
    void requestAssemblerValidatesRequiredInputAndPreservesRequestId() {
        GenerationRequestAssembler assembler = new GenerationRequestAssembler();
        assertThrows(IllegalArgumentException.class, () -> assembler.toCommand(null));
        assertThrows(IllegalArgumentException.class,
                () -> assembler.toCommand(new ResourceGenerationRequest(null, "CNN", null, null, null, null)));

        ResourceGenerationRequest request = new ResourceGenerationRequest(
                9L, "CNN", "profile", "padding", "focused", List.of(ArtifactType.QUIZ), "request-9");
        GenerationCommand command = assembler.toCommand(request);
        assertEquals("request-9", command.requestId());
        assertEquals(request, assembler.toRequest(command));

        GenerationCommand noTypes = new GenerationCommand(9L, "CNN", null, null, null, null, "request-10");
        assertNull(assembler.toRequest(noTypes).resourceTypes());
    }
}
