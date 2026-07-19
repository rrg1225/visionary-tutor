package com.visionary.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.AgentRunStep;
import com.visionary.entity.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentQualityEvaluationServiceTest {

    private final AgentQualityEvaluationService service =
            new AgentQualityEvaluationService(new ObjectMapper());

    @Test
    void scoreAwardsFullMarksOnlyForInspectableLiveEvidence() {
        List<GeneratedArtifact> artifacts = Arrays.stream(GeneratedArtifact.ArtifactType.values())
                .map(this::groundedLiveArtifact)
                .toList();
        AgentRunStep step = step("""
                {
                  "schemaVersion":"agent-audit-v2",
                  "toolCalls":[{"name":"generate_lecture_handout","success":true}],
                  "ragEvidence":["cite-course-1"],
                  "personalizationEvidence":{
                    "topicPresent":true,
                    "profilePresent":true,
                    "weakPointsPresent":true,
                    "toolSelectionConstrained":true
                  },
                  "qualitySignals":{"toolSucceeded":true}
                }
                """);

        AgentQualityEvaluationService.QualityScore score = service.score(List.of(step), artifacts);

        assertEquals(1D, score.coverage());
        assertEquals(1D, score.citationRate());
        assertEquals(1D, score.personalizationMatch());
        assertEquals(1D, score.executability());
        assertEquals(1D, score.overall());
    }

    @Test
    void scoreDoesNotTreatFavorableSummaryKeywordsAsPersonalizationEvidence() {
        AgentRunStep step = step("{}");
        step.setInputSummary("learner profile weak points emotion style mastery topic");
        GeneratedArtifact artifact = groundedLiveArtifact(GeneratedArtifact.ArtifactType.HANDOUT);
        artifact.setContentJson("{}");

        AgentQualityEvaluationService.QualityScore score = service.score(List.of(step), List.of(artifact));

        assertEquals(0D, score.personalizationMatch());
    }

    @Test
    void scorePenalizesDemoBlockedAndMalformedEvidence() {
        GeneratedArtifact artifact = groundedLiveArtifact(GeneratedArtifact.ArtifactType.HANDOUT);
        artifact.setContentJson("{\"origin\":\"DEMO\",\"degraded\":false,\"personalized\":false}");
        artifact.setCitationsJson("not-json");
        artifact.setValidationStatus("INVALID_CITATION");
        artifact.setPublishStatus("BLOCKED");
        AgentRunStep failedStep = step("{\"qualitySignals\":{\"toolSucceeded\":false}}");
        failedStep.setStatus("ERROR");

        AgentQualityEvaluationService.QualityScore score = service.score(
                List.of(failedStep),
                List.of(artifact)
        );

        assertEquals(0D, score.citationRate());
        assertEquals(0D, score.personalizationMatch());
        assertEquals(0D, score.executability());
        assertTrue(score.overall() < 0.1D);
    }

    private GeneratedArtifact groundedLiveArtifact(GeneratedArtifact.ArtifactType type) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setArtifactType(type);
        artifact.setContentMarkdown("# Verified content");
        artifact.setContentJson("""
                {"origin":"LIVE","generation_mode":"REACT_MULTI_AGENT","degraded":false,"personalized":true}
                """);
        artifact.setCitationsJson("[{\"citationId\":\"cite-course-1\"}]");
        artifact.setValidationStatus("GROUNDED");
        artifact.setPublishStatus("PUBLISHED");
        return artifact;
    }

    private AgentRunStep step(String auditJson) {
        AgentRunStep step = new AgentRunStep();
        step.setAgentName("ReActSupervisor");
        step.setStatus("OBSERVATION");
        step.setAuditTraceJson(auditJson);
        return step;
    }
}
