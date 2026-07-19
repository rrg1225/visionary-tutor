package com.visionary.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.Tool;
import com.visionary.agent.core.ToolContext;
import com.visionary.agent.core.ToolResult;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.service.LearningEvidenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real Tool that persists generated artifacts (handout, quiz, mindmap, etc.)
 * using the existing GeneratedArtifactRepository.
 */
@Slf4j
@Component
public class ArtifactPersistTool implements Tool {

    private final GeneratedArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final LearningSessionRepository learningSessionRepository;
    private final LearningEvidenceService learningEvidenceService;

    @Autowired
    public ArtifactPersistTool(GeneratedArtifactRepository artifactRepository, ObjectMapper objectMapper,
                               LearningSessionRepository learningSessionRepository,
                               LearningEvidenceService learningEvidenceService) {
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
        this.learningSessionRepository = learningSessionRepository;
        this.learningEvidenceService = learningEvidenceService;
    }

    /** Compatibility constructor for isolated legacy workers; evidence is recorded by their owning service. */
    public ArtifactPersistTool(GeneratedArtifactRepository artifactRepository, ObjectMapper objectMapper) {
        this(artifactRepository, objectMapper, null, null);
    }

    @Override
    public String getName() {
        return "ArtifactPersistTool";
    }

    @Override
    public String getDescription() {
        return "Persist a generated learning resource (HANDOUT, QUIZ, MINDMAP, etc.) to the database. " +
               "Input: {\"learningSessionId\": 123, \"type\": \"HANDOUT\", \"title\": \"...\", \"content\": \"...\"}";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("learningSessionId").put("type", "number");
        props.putObject("type").put("type", "string");
        props.putObject("title").put("type", "string");
        props.putObject("content").put("type", "string");
        props.putObject("contentJson").put("type", "string");
        schema.putArray("required").add("learningSessionId").add("type").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        try {
            Long sessionId = arguments.path("learningSessionId").asLong();
            String type = arguments.path("type").asText();
            String title = arguments.path("title").asText();
            String content = arguments.path("content").asText();

            if (sessionId == 0 || type.isBlank() || title.isBlank()) {
                return new ToolResult(false, "Missing required fields", Map.of());
            }

            String runId = context.runId();
            if ((runId == null || runId.isBlank()) && context.blackboard() != null) {
                runId = context.blackboard().getRunId();
            }

            GeneratedArtifact.ArtifactType artifactType = GeneratedArtifact.ArtifactType.valueOf(type);
            GeneratedArtifact artifact = null;
            if (runId != null && !runId.isBlank()) {
                artifact = artifactRepository.findByRunIdOrderByIdAsc(runId).stream()
                        .filter(item -> sessionId.equals(item.getLearningSessionId())
                                && item.getArtifactType() == artifactType)
                        .reduce((first, second) -> second)
                        .orElse(null);
            }

            if (artifact == null) {
                artifact = new GeneratedArtifact();
                artifact.setLearningSessionId(sessionId);
                artifact.setArtifactType(artifactType);
                artifact.setRunId(runId);
                artifact.setValidationStatus("UNVERIFIED");
            }

            artifact.setTitle(title);
            artifact.setContentMarkdown(content);
            String contentJson = arguments.path("contentJson").asText("");
            if (!contentJson.isBlank()) {
                artifact.setContentJson(contentJson);
            }

            GeneratedArtifact saved = artifactRepository.save(artifact);
            String evidenceRunId = runId;
            if (learningSessionRepository != null && learningEvidenceService != null) {
                learningSessionRepository.findById(sessionId).ifPresent(session ->
                    learningEvidenceService.record(new LearningEvidenceService.Evidence(
                            session.getUserId(), sessionId, "GENERATED_RESOURCE", String.valueOf(saved.getId()),
                            null, null, null, null, null, null, null, evidenceRunId,
                            Map.<String, Object>of("artifactType", type, "title", title)
                    ))
                );
            }

            log.info("[ArtifactPersistTool] Saved artifact id={} type={} session={}",
                    saved.getId(), type, sessionId);
            return new ToolResult(true, "Artifact persisted",
                    Map.of("artifactId", saved.getId(), "type", type));

        } catch (Exception e) {
            log.error("[ArtifactPersistTool] Failed to persist artifact: {}", e.getMessage());
            return new ToolResult(false, "Failed: " + e.getMessage(), Map.of());
        }
    }
}
