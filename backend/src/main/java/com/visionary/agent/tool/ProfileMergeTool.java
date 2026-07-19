package com.visionary.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.agent.core.Tool;
import com.visionary.agent.core.ToolContext;
import com.visionary.agent.core.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Merges learner profile snapshot from task input into the shared blackboard
 * so all specialist agents share the same profile context.
 */
@Component
@RequiredArgsConstructor
public class ProfileMergeTool implements Tool {

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "ProfileMergeTool";
    }

    @Override
    public String getDescription() {
        return "Merge learner profile snapshot and weak points into SharedBlackboard. " +
               "Input: {\"learnerProfileSnapshot\":\"...\",\"weakPointsSnapshot\":\"...\"}";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("learnerProfileSnapshot").put("type", "string");
        props.putObject("weakPointsSnapshot").put("type", "string");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String profile = arguments.path("learnerProfileSnapshot").asText("");
        String weakPoints = arguments.path("weakPointsSnapshot").asText("");

        if (!profile.isBlank()) {
            blackboard.updateProfileSnapshot(profile);
        }
        if (!weakPoints.isBlank()) {
            blackboard.put("weakPointsSnapshot", weakPoints);
        }

        return new ToolResult(true, "Profile merged into blackboard", Map.of(
                "profileMerged", !profile.isBlank(),
                "weakPointsMerged", !weakPoints.isBlank()
        ));
    }
}
