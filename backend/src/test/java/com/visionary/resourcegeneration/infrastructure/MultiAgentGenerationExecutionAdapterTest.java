package com.visionary.resourcegeneration.infrastructure;

import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.application.GenerationCommand;
import com.visionary.resourcegeneration.application.GenerationRequestAssembler;
import com.visionary.resourcegeneration.domain.OrchestrationMode;
import com.visionary.service.LegacyGenerationEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiAgentGenerationExecutionAdapterTest {

    @Test
    void mapsTheCommandAndDelegatesOnlyTheSelectedMode() {
        LegacyGenerationEngine engine = mock(LegacyGenerationEngine.class);
        ResourceGenerationResponse response = new ResourceGenerationResponse("run", List.of(), List.of(), "ok");
        when(engine.generateWithStrategy(any(), eq(null), eq(OrchestrationMode.REACT))).thenReturn(response);
        MultiAgentGenerationExecutionAdapter adapter = new MultiAgentGenerationExecutionAdapter(
                engine, new GenerationRequestAssembler());
        GenerationCommand command = new GenerationCommand(1L, "CNN", "profile", "padding", null, List.of(), "id-1");

        assertEquals(response, adapter.execute(command, OrchestrationMode.REACT, null));
        verify(engine).generateWithStrategy(any(), eq(null), eq(OrchestrationMode.REACT));
    }
}
