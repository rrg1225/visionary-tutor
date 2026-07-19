package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.entity.GeneratedArtifact.ArtifactType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationRequestAssemblerTest {

    private final GenerationRequestAssembler assembler = new GenerationRequestAssembler();

    @Test
    void rejectsRetiredVideoScriptGeneration() {
        ResourceGenerationRequest request = new ResourceGenerationRequest(
                42L,
                "CNN",
                "profile",
                "weak points",
                "",
                List.of(ArtifactType.VIDEO_SCRIPT),
                "request-1"
        );

        assertThatThrownBy(() -> assembler.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VISUALIZATION");
    }
}
