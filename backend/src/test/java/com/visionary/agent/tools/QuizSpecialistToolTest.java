package com.visionary.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.AgentJsonParser;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.quiz.GeneratedQuizDocument;
import com.visionary.quiz.GeneratedQuizValidator;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizSpecialistToolTest {

    @Test
    void offlineFallbackStillReturnsValidatedStructuredQuiz() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeepSeekApiClient deepSeekApiClient = mock(DeepSeekApiClient.class);
        RagRetrievalService ragRetrievalService = mock(RagRetrievalService.class);
        when(deepSeekApiClient.isConfigured()).thenReturn(false);
        when(ragRetrievalService.retrieveForTask(any(), any())).thenReturn(RagRetrievalResult.empty());

        QuizSpecialistTool tool = new QuizSpecialistTool(
                objectMapper,
                deepSeekApiClient,
                ragRetrievalService,
                new AgentJsonParser(objectMapper)
        );

        JsonNode envelope = objectMapper.readTree(tool.generateQuiz(
                "memory-1", "卷积输出尺寸", "{}", "INTERMEDIATE", 4, 12L));
        GeneratedQuizDocument document = objectMapper.readValue(
                envelope.path("contentJson").asText(), GeneratedQuizDocument.class);

        assertEquals(GeneratedQuizDocument.SCHEMA_V1, envelope.path("schema").asText());
        assertEquals(4, document.questions().size());
        assertTrue(GeneratedQuizValidator.validate(document, 4).isEmpty());
        assertTrue(envelope.path("content").asText().contains("第1题"));
        assertEquals(List.of(), envelope.path("citations").findValuesAsText("citationId"));
    }
}
