package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningPathEdge;
import com.visionary.entity.LearningPathNode;
import com.visionary.repository.LearningPathEdgeRepository;
import com.visionary.repository.LearningPathNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningPathGraphServiceTest {

    private static final long SESSION_ID = 9001L;
    private static final long ARTIFACT_ID = 7001L;

    @Mock
    private LearningPathNodeRepository nodeRepository;
    @Mock
    private LearningPathEdgeRepository edgeRepository;
    @Mock
    private LearningPathStepService learningPathStepService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LearningPathGraphService service;

    @BeforeEach
    void setUp() {
        service = new LearningPathGraphService(
                objectMapper,
                nodeRepository,
                edgeRepository,
                learningPathStepService
        );
    }

    @Test
    void handleNodeFailureScopesUpdatesToSessionAndArtifact() throws Exception {
        List<LearningPathNode> nodes = List.of(
                node(1L, "n1", 1),
                node(2L, "n2", 2),
                node(3L, "n3", 3),
                node(4L, "n4", 4)
        );
        List<LearningPathEdge> edges = List.of(
                edge("n1", "n2", 1),
                edge("n2", "n3", 2),
                edge("n3", "n4", 3)
        );
        when(nodeRepository.findByArtifactIdOrderByOrderIndexAsc(ARTIFACT_ID)).thenReturn(nodes);
        when(edgeRepository.findByArtifactIdOrderByOrderIndexAsc(ARTIFACT_ID)).thenReturn(edges);

        ReplanTriggerService.ReplanResult result = service.handleNodeFailure(SESSION_ID, ARTIFACT_ID, "n3");

        assertTrue(result.triggered());
        assertTrue(result.message().contains("session=9001"));
        assertTrue(result.message().contains("artifact=7001"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearningPathNode>> captor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(captor.capture());
        Map<String, LearningPathNode> saved = captor.getValue().stream()
                .collect(Collectors.toMap(LearningPathNode::getNodeKey, Function.identity()));

        assertEquals("NEEDS_REINFORCEMENT", status(saved.get("n1")));
        assertEquals("NEEDS_REINFORCEMENT", status(saved.get("n2")));
        assertEquals("FAILED", status(saved.get("n3")));
        assertEquals("BLOCKED", status(saved.get("n4")));
        verify(nodeRepository, never()).findByLearningSessionId(SESSION_ID);
        verify(edgeRepository, never()).findByLearningSessionId(SESSION_ID);
    }

    @Test
    void handleNodeFailureRejectsArtifactFromAnotherSession() {
        LearningPathNode foreignNode = node(3L, "n3", 3);
        foreignNode.setLearningSessionId(SESSION_ID + 1);
        when(nodeRepository.findByArtifactIdOrderByOrderIndexAsc(ARTIFACT_ID))
                .thenReturn(List.of(foreignNode));

        ReplanTriggerService.ReplanResult result = service.handleNodeFailure(SESSION_ID, ARTIFACT_ID, "n3");

        assertFalse(result.triggered());
        assertTrue(result.message().contains("not found"));
        verify(nodeRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(edgeRepository, never()).findByArtifactIdOrderByOrderIndexAsc(ARTIFACT_ID);
    }

    @Test
    void persistGraphDeletesExistingGraphBeforeSavingReplacement() {
        GeneratedArtifact artifact = artifact();

        service.persistGraph(artifact, graphJson());

        InOrder writes = inOrder(edgeRepository, nodeRepository, learningPathStepService);
        writes.verify(edgeRepository).deleteByArtifactId(ARTIFACT_ID);
        writes.verify(nodeRepository).deleteByArtifactId(ARTIFACT_ID);
        writes.verify(nodeRepository).saveAll(anyList());
        writes.verify(edgeRepository).saveAll(anyList());
        writes.verify(learningPathStepService).syncStepsFromArtifact(artifact);
    }

    @Test
    void persistGraphPropagatesDatabaseFailureSoTransactionCanRollBack() {
        GeneratedArtifact artifact = artifact();
        when(nodeRepository.saveAll(anyList()))
                .thenThrow(new DataIntegrityViolationException("duplicate graph node"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> service.persistGraph(artifact, graphJson())
        );

        verify(edgeRepository, never()).saveAll(anyList());
        verify(learningPathStepService, never()).syncStepsFromArtifact(artifact);
    }

    private GeneratedArtifact artifact() {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setId(ARTIFACT_ID);
        artifact.setLearningSessionId(SESSION_ID);
        return artifact;
    }

    private String graphJson() {
        return """
                {
                  "nodes": [
                    {"id":"n1","label":"Start","order":1},
                    {"id":"n2","label":"Finish","order":2}
                  ],
                  "edges": [
                    {"from":"n1","to":"n2","type":"PREREQUISITE","order":1}
                  ]
                }
                """;
    }

    private LearningPathNode node(Long id, String key, int order) {
        LearningPathNode node = new LearningPathNode();
        node.setId(id);
        node.setArtifactId(ARTIFACT_ID);
        node.setLearningSessionId(SESSION_ID);
        node.setNodeKey(key);
        node.setLabel(key);
        node.setOrderIndex(order);
        node.setMetadataJson("{\"rationale\":\"test\"}");
        return node;
    }

    private LearningPathEdge edge(String from, String to, int order) {
        LearningPathEdge edge = new LearningPathEdge();
        edge.setArtifactId(ARTIFACT_ID);
        edge.setLearningSessionId(SESSION_ID);
        edge.setFromNodeKey(from);
        edge.setToNodeKey(to);
        edge.setRelationType("PREREQUISITE");
        edge.setOrderIndex(order);
        return edge;
    }

    private String status(LearningPathNode node) throws Exception {
        JsonNode metadata = objectMapper.readTree(node.getMetadataJson());
        return metadata.path("status").asText();
    }
}
