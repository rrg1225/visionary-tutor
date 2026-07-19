package com.visionary.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.Tool;
import com.visionary.agent.core.ToolContext;
import com.visionary.agent.core.ToolResult;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real Tool implementation that delegates to the existing RagRetrievalService.
 * This makes RAG retrieval available to any Agent via the Tool interface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetrieveTool implements Tool {

    private final RagRetrievalService ragRetrievalService;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "RAGRetrieveTool";
    }

    @Override
    public String getDescription() {
        return "Retrieve grounded knowledge from the course knowledge base using vector search. " +
               "Input: {\"query\": \"...\", \"taskType\": \"RESOURCE_GENERATION|KNOWLEDGE_DIAGNOSIS\"}. " +
               "Returns applicationContext, algorithmContext, mathContext and citations.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("taskType").put("type", "string").put("default", "RESOURCE_GENERATION");
        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        try {
            String query = arguments.path("query").asText("");
            String taskTypeStr = arguments.path("taskType").asText("RESOURCE_GENERATION");

            com.visionary.agent.AgentTaskType taskType = switch (taskTypeStr) {
                case "KNOWLEDGE_DIAGNOSIS" -> com.visionary.agent.AgentTaskType.KNOWLEDGE_DIAGNOSIS;
                default -> com.visionary.agent.AgentTaskType.RESOURCE_GENERATION;
            };

            RagRetrievalResult result = ragRetrievalService.retrieveForTask(taskType, query);

            Map<String, Object> data = Map.of(
                    "applicationContext", result.applicationContext(),
                    "algorithmContext", result.algorithmContext(),
                    "mathContext", result.mathContext(),
                    "citations", result.citations(),
                    "grounded", result.hasGroundedEvidence()
            );

            return new ToolResult(true, "RAG retrieval completed", data);
        } catch (Exception e) {
            log.warn("RagRetrieveTool failed: {}", e.getMessage());
            return new ToolResult(false, "RAG retrieval failed: " + e.getMessage(), Map.of());
        }
    }
}