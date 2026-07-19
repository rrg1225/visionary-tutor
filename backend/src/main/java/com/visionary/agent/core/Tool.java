package com.visionary.agent.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tool interface - the only way Agents interact with external capabilities (RAG, video gen, profile update, etc.).
 */
public interface Tool {

    String getName();

    String getDescription();           // Used for LLM tool description

    JsonNode getParametersSchema();

    ToolResult execute(JsonNode arguments, ToolContext context);
}